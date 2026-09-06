// Standalone test harness for core/memory/: loads whatever memory exists,
// splices it into the system prompt, runs a chat session, then on exit asks
// the model to extract/summarize that session back into memory. Phase 3 of
// PLAN.md calls for this to be testable standalone before any mobile UI
// touches it, same as core/inference was in Phase 1 (see chat_cli.cpp).
#include "pocketchat_inference.h"
#include "pocketchat_memory.h"

#include <cstdio>
#include <iostream>
#include <string>
#include <vector>

namespace {

void print_usage(const char * argv0) {
    fprintf(stderr,
        "usage: %s -m model.gguf -d memory_dir [-c n_ctx] [-t n_threads] [-ngl n_gpu_layers]\n"
        "       %s -d memory_dir -s <query>   (FR-026 search test, no model needed)\n",
        argv0, argv0);
}

int collect_and_print(const char * piece, void * user_data) {
    static_cast<std::string *>(user_data)->append(piece);
    fputs(piece, stdout);
    fflush(stdout);
    return 1;
}

// Prints a header the first time each phase's tokens start arriving, so the
// live memory-update stream reads like a small transcript of its own.
struct memory_progress_state {
    pc_memory_phase last_phase = static_cast<pc_memory_phase>(-1);
};

int print_memory_progress(pc_memory_phase phase, const char * piece, void * user_data) {
    auto * state = static_cast<memory_progress_state *>(user_data);
    if (phase != state->last_phase) {
        state->last_phase = phase;
        printf("\n[%s]\n", phase == PC_MEMORY_PHASE_EXTRACTING_FACTS ? "extracting facts" : "summarizing");
    }
    fputs(piece, stdout);
    fflush(stdout);
    return 1;
}

} // namespace

int main(int argc, char ** argv) {
    std::string model_path;
    std::string memory_dir;
    std::string search_query;
    uint32_t    n_ctx        = 0;
    int32_t     n_threads    = -1;
    int32_t     n_gpu_layers = 0;

    for (int i = 1; i < argc; i++) {
        const std::string arg = argv[i];
        auto next = [&]() -> std::string {
            if (i + 1 >= argc) {
                print_usage(argv[0]);
                std::exit(1);
            }
            return argv[++i];
        };

        if      (arg == "-m")   model_path   = next();
        else if (arg == "-d")   memory_dir   = next();
        else if (arg == "-s")   search_query = next();
        else if (arg == "-c")   n_ctx        = (uint32_t) std::stoul(next());
        else if (arg == "-t")   n_threads    = std::stoi(next());
        else if (arg == "-ngl") n_gpu_layers = std::stoi(next());
        else {
            print_usage(argv[0]);
            return 1;
        }
    }

    if (memory_dir.empty()) {
        print_usage(argv[0]);
        return 1;
    }

    // FR-026: search doesn't touch the model at all, so it's handled as its
    // own early-exit path rather than folding it into the chat-session flow
    // below (which does require one).
    if (!search_query.empty()) {
        pc_memory_search_result * results = nullptr;
        size_t count = 0;
        const int rc = pc_memory_search(memory_dir.c_str(), search_query.c_str(), 10, &results, &count);
        if (rc != 0) {
            fprintf(stderr, "search failed: %s\n", pc_memory_last_error());
            return 1;
        }
        printf("%zu match(es) for \"%s\":\n", count, search_query.c_str());
        for (size_t i = 0; i < count; i++) {
            printf("[%s] %s\n", results[i].timestamp, results[i].content);
            if (results[i].annotation[0] != '\0') printf("  note: %s\n", results[i].annotation);
        }
        pc_memory_free_search_results(results, count);
        return 0;
    }

    if (model_path.empty()) {
        print_usage(argv[0]);
        return 1;
    }

    pc_init();

    pc_model * model = pc_model_load(model_path.c_str(), n_gpu_layers);
    if (!model) {
        fprintf(stderr, "error: %s\n", pc_last_error());
        return 1;
    }

    pc_context * ctx = pc_context_create(model, n_ctx, n_threads);
    if (!ctx) {
        fprintf(stderr, "error: %s\n", pc_last_error());
        pc_model_free(model);
        return 1;
    }

    const std::string base_system_prompt = "You are PocketChat, a helpful assistant.";

    // history[0] is always the injected-memory system turn — kept separate
    // from what gets summarized later, since pc_memory_update_session()
    // already reads the existing profile.txt itself to merge against.
    // Rebuilt every turn (see below) rather than once here, so FR-013's
    // relevance search actually has a query — the user's just-typed
    // message — to rank summaries against.
    std::vector<std::pair<std::string, std::string>> history;
    history.emplace_back("system", base_system_prompt);

    const pc_sampling_params sampling = pc_sampling_default_params();

    printf("pocketchat memory_cli - context: %u tokens. Empty line to end the session.\n", pc_context_n_ctx(ctx));

    while (true) {
        printf("\n> ");
        std::string user;
        if (!std::getline(std::cin, user) || user.empty()) {
            break;
        }

        // FR-013: rank injected summaries by relevance to this message
        // instead of pure recency (falls back to recency automatically —
        // see pc_memory_build_context's doc comment — so this is also
        // exactly what the very first turn above already exercised).
        char * remembered = pc_memory_build_context(memory_dir.c_str(), user.c_str(), /*max_summaries=*/5, /*max_chars=*/2000);
        std::string system_prompt = base_system_prompt;
        if (remembered && remembered[0] != '\0') {
            printf("--- injected memory context (query: \"%s\") ---\n%s--------------------------------\n",
                   user.c_str(), remembered);
            system_prompt += "\n\n";
            system_prompt += remembered;
        }
        if (remembered) pc_memory_free_string(remembered);
        history[0].second = system_prompt;

        history.emplace_back("user", user);

        std::vector<pc_chat_message> messages;
        messages.reserve(history.size());
        for (auto & turn : history) {
            messages.push_back({ turn.first.c_str(), turn.second.c_str() });
        }

        printf("\n");
        std::string response;
        const int rc = pc_generate_chat(ctx, messages.data(), messages.size(), sampling, collect_and_print, &response);
        printf("\n");

        if (rc != 0) {
            fprintf(stderr, "generation error: %s\n", pc_last_error());
            history.pop_back();
            continue;
        }
        history.emplace_back("assistant", response);
    }

    if (history.size() > 1) {
        std::vector<pc_chat_message> messages;
        messages.reserve(history.size() - 1);
        for (size_t i = 1; i < history.size(); i++) { // skip the system turn
            messages.push_back({ history[i].first.c_str(), history[i].second.c_str() });
        }

        printf("\nupdating memory from this session...");
        memory_progress_state progress_state;
        const int rc = pc_memory_update_session(model, memory_dir.c_str(), messages.data(), messages.size(),
                                                 n_ctx, n_threads, print_memory_progress, &progress_state);
        printf("\n");
        if (rc != 0) {
            fprintf(stderr, "memory update failed: %s\n", pc_memory_last_error());
        } else {
            printf("done. check %s/profile.txt and %s/summaries/\n", memory_dir.c_str(), memory_dir.c_str());
        }
    }

    pc_context_free(ctx);
    pc_model_free(model);
    pc_shutdown();
    return 0;
}
