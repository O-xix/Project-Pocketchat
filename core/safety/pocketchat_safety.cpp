#include "pocketchat_safety.h"

#include <array>
#include <cctype>
#include <cstddef>
#include <queue>
#include <string>
#include <vector>

namespace {

struct Pattern {
    const char * text;
    const char * category;
};

// Starter set of literal markers for the clearest, most universally-recognized
// dual-use hazard categories — named agents/devices, never synthesis
// instructions, ratios, or steps. Deliberately not exhaustive: this is tier 1
// of a defense-in-depth stack (see the file header comment), meant to catch
// the obvious case cheaply, not to be a comprehensive safety solution on its
// own. Extend this list as real gaps are found.
constexpr Pattern kPatterns[] = {
    // Chemical / nerve agents
    { "sarin", "chemical-weapons" },
    { "vx nerve agent", "chemical-weapons" },
    { "tabun nerve agent", "chemical-weapons" },
    { "soman nerve agent", "chemical-weapons" },
    { "novichok", "chemical-weapons" },
    { "mustard gas weapon", "chemical-weapons" },
    { "chlorine gas weapon", "chemical-weapons" },
    // Biological agents / weaponization
    { "weaponize anthrax", "biological-weapons" },
    { "anthrax weaponization", "biological-weapons" },
    { "ricin extraction", "biological-weapons" },
    { "weaponized ricin", "biological-weapons" },
    { "botulinum toxin weapon", "biological-weapons" },
    { "weaponize smallpox", "biological-weapons" },
    // Explosives / IEDs
    { "triacetone triperoxide", "explosives" },
    { "tatp synthesis", "explosives" },
    { "rdx synthesis", "explosives" },
    { "petn synthesis", "explosives" },
    { "semtex synthesis", "explosives" },
    { "pipe bomb", "explosives" },
    { "pressure cooker bomb", "explosives" },
    { "dirty bomb", "explosives" },
    { "improvised explosive device", "explosives" },
    { "thermite grenade", "explosives" },
    { "nitroglycerin synthesis", "explosives" },
    { "ammonium nitrate bomb", "explosives" },
    { "anfo explosive", "explosives" },
};

constexpr size_t kNumPatterns = sizeof(kPatterns) / sizeof(kPatterns[0]);

// Full-transition-table Aho-Corasick automaton, built once at startup. With
// ~25 short patterns this is a few hundred nodes at 1KB each (256 int edges)
// -- comfortably under the tier's memory budget and fast enough that
// pc_safety_check() finishes in well under a millisecond per call.
struct AhoCorasick {
    struct Node {
        std::array<int, 256> next;
        int                   fail          = 0;
        int                   pattern_index = -1; // index into kPatterns, or -1
    };

    std::vector<Node> nodes;

    AhoCorasick() {
        add_node();
        for (size_t i = 0; i < kNumPatterns; i++) {
            add_pattern(kPatterns[i].text, (int) i);
        }
        build_failure_links();
    }

    int add_node() {
        nodes.emplace_back();
        nodes.back().next.fill(-1);
        return (int) nodes.size() - 1;
    }

    void add_pattern(const char * text, int pattern_index) {
        int node = 0;
        for (const char * p = text; *p; p++) {
            const unsigned char c = (unsigned char) std::tolower((unsigned char) *p);
            if (nodes[node].next[c] == -1) {
                nodes[node].next[c] = add_node();
            }
            node = nodes[node].next[c];
        }
        nodes[node].pattern_index = pattern_index;
    }

    void build_failure_links() {
        std::queue<int> q;
        for (int c = 0; c < 256; c++) {
            if (nodes[0].next[c] == -1) {
                nodes[0].next[c] = 0;
            } else {
                nodes[nodes[0].next[c]].fail = 0;
                q.push(nodes[0].next[c]);
            }
        }
        while (!q.empty()) {
            const int u = q.front();
            q.pop();
            for (int c = 0; c < 256; c++) {
                const int v = nodes[u].next[c];
                if (v == -1) {
                    nodes[u].next[c] = nodes[nodes[u].fail].next[c];
                } else {
                    nodes[v].fail = nodes[nodes[u].fail].next[c];
                    q.push(v);
                }
            }
        }
    }

    // Returns the index into kPatterns of the first match, or -1 if none.
    int find_first_match(const std::string & text) const {
        int node = 0;
        for (unsigned char raw : text) {
            const unsigned char c = (unsigned char) std::tolower(raw);
            node = nodes[node].next[c];
            for (int f = node; f != 0; f = nodes[f].fail) {
                if (nodes[f].pattern_index != -1) {
                    return nodes[f].pattern_index;
                }
            }
        }
        return -1;
    }
};

const AhoCorasick & automaton() {
    static const AhoCorasick instance;
    return instance;
}

thread_local std::string g_last_category;

} // namespace

int pc_safety_check(const char * text) {
    if (!text) return 0;

    const int idx = automaton().find_first_match(text);
    if (idx < 0) {
        g_last_category.clear();
        return 0;
    }
    g_last_category = kPatterns[idx].category;
    return 1;
}

const char * pc_safety_last_category(void) {
    return g_last_category.c_str();
}
