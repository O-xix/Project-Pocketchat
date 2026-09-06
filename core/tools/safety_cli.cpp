// Standalone REPL for exercising core/safety/ directly on this machine, no
// model or mobile UI needed -- the filter has no dependency on either.
#include "pocketchat_safety.h"

#include <cstdio>
#include <iostream>
#include <string>

int main() {
    printf("pocketchat safety_cli - one line per check, empty line to quit.\n");

    std::string line;
    while (true) {
        printf("\n> ");
        if (!std::getline(std::cin, line) || line.empty()) {
            break;
        }

        if (pc_safety_check(line.c_str())) {
            printf("BLOCKED (category: %s)\n", pc_safety_last_category());
        } else {
            printf("clean\n");
        }
    }
    return 0;
}
