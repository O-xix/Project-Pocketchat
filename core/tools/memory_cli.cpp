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
    fprintf(stderr, "usage: %s -m model.gguf -d memory_dir [-c n_ctx] [-t n_threads] [-ngl n_gpu_layers]\n", argv0);
}

int collect_and_print(const char * piece, void * user_data) {
    static_cast<std::string *>(user_data)->append(piece);
    fputs(piece, stdout);
    fflush(stdout);
    return 1;
}

} // namespace

int main(int argc, char ** argv) {
    std::string model_path;
    std::string memory_dir;
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
        else if (arg == "-c")   n_ctx        = (uint32_t) std::stoul(next());
        else if (arg == "-t")   n_threads    = std::stoi(next());
        else if (arg == "-ngl") n_gpu_layers = std::stoi(next());
        else {
            print_usage(argv[0]);
            return 1;
        }
    }

    if (model_path.empty() || memory_dir.empty()) {
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

    char * remembered = pc_memory_build_context(memory_dir.c_str(), /*max_summaries=*/5, /*max_chars=*/2000);
    std::string system_prompt = "You are PocketChat, a helpful assistant.";
    if (remembered && remembered[0] != '\0') {
        printf("--- injected memory context ---\n%s--------------------------------\n", remembered);
        system_prompt += "\n\n";
        system_prompt += remembered;
    } else {
        printf("--- no prior memory found in %s ---\n", memory_dir.c_str());
    }
    if (remembered) pc_memory_free_string(remembered);

    // history[0] is always this injected-memory system turn — kept separate
    // from what gets summarized later, since pc_memory_update_session()
    // already reads the existing profile.txt itself to merge against.
    std::vector<std::pair<std::string, std::string>> history;
    history.emplace_back("system", system_prompt);

    const pc_sampling_params sampling = pc_sampling_default_params();

    printf("pocketchat memory_cli - context: %u tokens. Empty line to end the session.\n", pc_context_n_ctx(ctx));

    while (true) {
        printf("\n> ");
        std::string user;
        if (!std::getline(std::cin, user) || user.empty()) {
            break;
        }
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

        printf("\nupdating memory from this session...\n");
        const int rc = pc_memory_update_session(model, memory_dir.c_str(), messages.data(), messages.size(), n_ctx, n_threads);
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
