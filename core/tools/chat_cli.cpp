// Standalone REPL for exercising core/inference/ directly on this machine,
// without any mobile UI in the loop. Phase 1 of PLAN.md calls for the
// inference wrapper to be testable this way before Android/iOS work starts.
#include "pocketchat_inference.h"

#include <cstdio>
#include <iostream>
#include <string>
#include <vector>

namespace {

void print_usage(const char * argv0) {
    fprintf(stderr, "usage: %s -m model.gguf [-c n_ctx] [-t n_threads] [-ngl n_gpu_layers]\n", argv0);
}

int collect_and_print(const char * piece, void * user_data) {
    static_cast<std::string *>(user_data)->append(piece);
    fputs(piece, stdout);
    fflush(stdout);
    return 1; // keep generating
}

} // namespace

int main(int argc, char ** argv) {
    std::string model_path;
    uint32_t    n_ctx         = 0;
    int32_t     n_threads     = -1;
    int32_t     n_gpu_layers  = 0;

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
        else if (arg == "-c")   n_ctx        = (uint32_t) std::stoul(next());
        else if (arg == "-t")   n_threads    = std::stoi(next());
        else if (arg == "-ngl") n_gpu_layers = std::stoi(next());
        else {
            print_usage(argv[0]);
            return 1;
        }
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

    printf("pocketchat chat_cli - context: %u tokens. Empty line to quit.\n", pc_context_n_ctx(ctx));

    std::vector<std::pair<std::string, std::string>> history; // {role, content}, in order
    const pc_sampling_params sampling = pc_sampling_default_params();

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

    pc_context_free(ctx);
    pc_model_free(model);
    pc_shutdown();
    return 0;
}
