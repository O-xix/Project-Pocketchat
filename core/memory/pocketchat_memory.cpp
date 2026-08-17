#include "pocketchat_memory.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

namespace fs = std::filesystem;

namespace {

thread_local std::string g_last_error;

void set_error(std::string msg) {
    g_last_error = std::move(msg);
}

std::string trim(const std::string & s) {
    const size_t start = s.find_first_not_of(" \t\n\r");
    if (start == std::string::npos) return "";
    const size_t end = s.find_last_not_of(" \t\n\r");
    return s.substr(start, end - start + 1);
}

std::string read_file(const fs::path & path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return "";
    std::ostringstream ss;
    ss << f.rdbuf();
    return ss.str();
}

bool write_file(const fs::path & path, const std::string & content) {
    std::ofstream f(path, std::ios::binary | std::ios::trunc);
    if (!f) return false;
    f << content;
    return f.good();
}

std::string render_transcript(const pc_chat_message * messages, size_t n) {
    std::ostringstream ss;
    for (size_t i = 0; i < n; i++) {
        ss << messages[i].role << ": " << messages[i].content << "\n";
    }
    return ss.str();
}

std::string timestamp_filename() {
    const auto now = std::chrono::system_clock::now();
    const std::time_t t = std::chrono::system_clock::to_time_t(now);
    std::tm tm{};
#if defined(_WIN32)
    gmtime_s(&tm, &t);
#else
    gmtime_r(&t, &tm);
#endif
    char buf[32];
    std::snprintf(buf, sizeof(buf), "%04d%02d%02d-%02d%02d%02d.txt",
                  tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday,
                  tm.tm_hour, tm.tm_min, tm.tm_sec);
    return buf;
}

struct progress_relay_state {
    std::string                    response;
    pc_memory_phase                phase;
    pc_memory_progress_callback    callback; // may be null
    void                          * user_data;
};

int progress_relay_callback(const char * piece, void * user_data) {
    auto * state = static_cast<progress_relay_state *>(user_data);
    state->response += piece;
    if (state->callback) {
        return state->callback(state->phase, piece, state->user_data);
    }
    return 1;
}

// Runs a single one-shot instruction prompt against `ctx` (already reset to a
// clean state by the caller) and returns the trimmed response, or an empty
// string on failure (with pc_last_error() set by the inference layer).
// Streams each generated piece through `progress_cb` (if non-null), tagged
// with `phase`, as it's produced.
std::string run_prompt(
    pc_context                  * ctx,
    const std::string           & prompt,
    pc_sampling_params            sampling,
    pc_memory_phase                phase,
    pc_memory_progress_callback   progress_cb,
    void                        * progress_ud
) {
    const pc_chat_message msg{ "user", prompt.c_str() };
    progress_relay_state state{ "", phase, progress_cb, progress_ud };
    const int rc = pc_generate_chat(ctx, &msg, 1, sampling, progress_relay_callback, &state);
    if (rc != 0) {
        set_error(std::string("generation failed: ") + pc_last_error());
        return "";
    }
    return trim(state.response);
}

} // namespace

char * pc_memory_build_context(const char * memory_dir, int max_summaries, size_t max_chars) {
    if (!memory_dir) {
        set_error("pc_memory_build_context: memory_dir is null");
        return nullptr;
    }

    const fs::path dir(memory_dir);
    std::ostringstream out;

    const std::string profile = trim(read_file(dir / "profile.txt"));
    if (!profile.empty()) {
        out << "What you remember about the user:\n" << profile << "\n";
    }

    const fs::path summaries_dir = dir / "summaries";
    std::vector<fs::path> files;
    std::error_code ec;
    if (fs::exists(summaries_dir, ec) && fs::is_directory(summaries_dir, ec)) {
        for (const auto & entry : fs::directory_iterator(summaries_dir, ec)) {
            if (entry.is_regular_file() && entry.path().extension() == ".txt") {
                files.push_back(entry.path());
            }
        }
    }
    // Filenames are zero-padded UTC timestamps (see timestamp_filename), so a
    // lexical sort is also a chronological one.
    std::sort(files.begin(), files.end());

    if (max_summaries > 0 && !files.empty()) {
        const size_t start = files.size() > (size_t) max_summaries ? files.size() - (size_t) max_summaries : 0;
        out << "\nRecent session summaries:\n";
        for (size_t i = start; i < files.size(); i++) {
            const std::string s = trim(read_file(files[i]));
            if (!s.empty()) out << "- " << s << "\n";
        }
    }

    std::string result = out.str();
    if (result.size() > max_chars) {
        // Keep the tail (most recent content) over the head.
        result = result.substr(result.size() - max_chars);
    }

    char * buf = static_cast<char *>(std::malloc(result.size() + 1));
    if (!buf) {
        set_error("pc_memory_build_context: allocation failed");
        return nullptr;
    }
    std::memcpy(buf, result.c_str(), result.size() + 1);
    return buf;
}

void pc_memory_free_string(char * s) {
    std::free(s);
}

int pc_memory_update_session(
    pc_model                     * model,
    const char                    * memory_dir,
    const pc_chat_message         * messages,
    size_t                          n_messages,
    uint32_t                        n_ctx,
    int32_t                         n_threads,
    pc_memory_progress_callback     progress_callback,
    void                           * progress_user_data
) {
    if (!model || !memory_dir || !messages || n_messages == 0) {
        set_error("pc_memory_update_session: invalid arguments");
        return -1;
    }

    const fs::path dir(memory_dir);
    std::error_code ec;
    fs::create_directories(dir / "summaries", ec);

    pc_context * scratch = pc_context_create(model, n_ctx, n_threads);
    if (!scratch) {
        set_error(std::string("failed to create scratch context: ") + pc_last_error());
        return -1;
    }

    const std::string transcript = render_transcript(messages, n_messages);
    const std::string existing_profile = trim(read_file(dir / "profile.txt"));

    pc_sampling_params sampling = pc_sampling_default_params();
    sampling.temp = 0.3f; // more faithful/deterministic for extraction-style tasks than chat defaults

    // --- fact extraction: merge into profile.txt ---
    {
        std::ostringstream prompt;
        prompt <<
            "You are a memory-extraction assistant for a chat app. Given the "
            "user's existing stored profile (may be empty) and a transcript of "
            "a chat session, output an UPDATED profile: durable facts about the "
            "user worth remembering long-term (name, preferences, ongoing "
            "projects, recurring context) -- not one-off details from a single "
            "question. Merge new information with the existing profile; remove "
            "anything the new session contradicts. Output ONLY the updated "
            "profile as plain short lines, no headers, no meta-commentary. If "
            "nothing durable is worth remembering, output nothing.\n\n"
            "Existing profile:\n"
            << (existing_profile.empty() ? "(empty)" : existing_profile) << "\n\n"
            "Session transcript:\n" << transcript;

        const std::string updated = run_prompt(
            scratch, prompt.str(), sampling, PC_MEMORY_PHASE_EXTRACTING_FACTS, progress_callback, progress_user_data);
        if (!updated.empty()) {
            write_file(dir / "profile.txt", updated + "\n");
        }
        pc_context_reset(scratch);
    }

    // --- session summary: append to summaries/ ---
    {
        std::ostringstream prompt;
        prompt <<
            "Summarize the following chat session in 2-4 short sentences, "
            "focused on what was discussed or accomplished, for future "
            "reference. Output ONLY the summary, no headers, no "
            "meta-commentary.\n\n"
            "Session transcript:\n" << transcript;

        const std::string summary = run_prompt(
            scratch, prompt.str(), sampling, PC_MEMORY_PHASE_SUMMARIZING, progress_callback, progress_user_data);
        if (!summary.empty()) {
            write_file(dir / "summaries" / timestamp_filename(), summary + "\n");
        }
    }

    pc_context_free(scratch);
    return 0;
}

const char * pc_memory_last_error(void) {
    return g_last_error.c_str();
}
