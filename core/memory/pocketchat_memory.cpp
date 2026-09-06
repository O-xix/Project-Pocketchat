#include "pocketchat_memory.h"

#include <sqlite3.h>

#include <algorithm>
#include <cctype>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <string>
#include <unordered_set>
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

// A minimal, common-English stopword list (includes the single-letter
// fragments contractions like "what's"/"don't" split into, e.g. "s"/"t").
// Without this, a filler word shared by nearly every summary (e.g. "a", "s")
// would OR-match almost the whole corpus, drowning out the one document that
// actually shares a real topic word and defeating "fall back when there's no
// strong match" — bm25 alone doesn't save this for a tiny personal corpus,
// where a common word can appear in every single document.
bool is_stopword(const std::string & word) {
    static const std::unordered_set<std::string> kStopwords = {
        "a", "an", "and", "are", "as", "at", "be", "but", "by", "can", "could",
        "did", "do", "does", "for", "from", "had", "has", "have", "he", "her",
        "his", "how", "i", "if", "in", "into", "is", "it", "its", "me", "my",
        "no", "not", "of", "on", "or", "our", "she", "should", "so", "such",
        "that", "the", "their", "then", "there", "these", "they", "this",
        "to", "was", "we", "were", "what", "when", "where", "which", "who",
        "whom", "whose", "why", "will", "with", "would", "you", "your",
        "s", "t", "re", "ve", "ll", "m", "d",
    };
    return kStopwords.count(word) > 0;
}

// Splits `raw` into lowercased alphanumeric tokens (dropping stopwords, see
// is_stopword()) and safely quotes each one, so arbitrary free-text (a real
// chat message) can never be misparsed as FTS5 query-language syntax
// (AND/OR/NOT, bare quotes, column filters, etc.) — every token becomes a
// literal phrase match, ORed together. Returns "" if no usable tokens were
// found (e.g. the query was empty, pure punctuation, or entirely stopwords),
// which the caller treats as "nothing to search for."
std::string sanitize_fts5_query(const std::string & raw) {
    constexpr size_t kMaxTokens = 32; // bounds query size/cost; plenty for a single chat message
    std::ostringstream out;
    std::string token;
    size_t n_tokens = 0;

    auto flush_token = [&]() {
        if (!token.empty() && n_tokens < kMaxTokens && !is_stopword(token)) {
            if (n_tokens > 0) out << " OR ";
            out << '"' << token << '"';
            n_tokens++;
        }
        token.clear();
    };
    for (unsigned char c : raw) {
        if (std::isalnum(c)) {
            token += (char) std::tolower(c);
        } else {
            flush_token();
        }
    }
    flush_token();
    return out.str();
}

constexpr const char * kAnnotationSuffix = ".annotation.txt";

// fs::path::extension() only strips the *last* extension component, so an
// annotation file (e.g. "20260101-090000.annotation.txt") still reports
// ".txt" and would otherwise be scanned in as a phantom extra summary in its
// own right — this is what a plain ".txt" filter must additionally exclude.
bool is_annotation_file(const fs::path & p) {
    const std::string name = p.filename().string();
    const size_t suffix_len = std::strlen(kAnnotationSuffix);
    return name.size() >= suffix_len && name.compare(name.size() - suffix_len, suffix_len, kAnnotationSuffix) == 0;
}

// FR-023: a summary's user-authored annotation lives in a sibling file next
// to it — memory_dir/summaries/<timestamp>.annotation.txt — additive and
// separate from the model's own <timestamp>.txt (see pocketchat_memory.h's
// NFR-006 note). Empty string if there's no annotation.
std::string annotation_for(const fs::path & summary_file) {
    return trim(read_file(summary_file.parent_path() / (summary_file.stem().string() + kAnnotationSuffix)));
}

// A summary's annotation is weighted this many times higher than its own
// summary text when ranking FTS5 matches — a user-flagged note is presumably
// the highest-signal text for future recall (FR-023), so it should be able
// to surface a summary even when the model's own wording doesn't share
// vocabulary with the query.
constexpr double kAnnotationBm25Weight = 3.0;

// Tries to select up to `max_summaries` of `files` most relevant to `query`.
// Builds a throwaway in-memory FTS5 index from their current contents (and
// each one's annotation, if any — see annotation_for()) on every call — the
// .txt files are the single source of truth, so there's nothing to keep in
// sync and no on-disk index format to ever migrate; corpus sizes here (a
// personal on-device memory log) are small enough that rebuilding costs a
// negligible fraction of a millisecond.
// Returns the selected files in relevance order (best match first), or an
// empty vector if FTS5 isn't available, `query` has no usable search terms,
// or nothing matched — any of which tells the caller to fall back to its
// existing recency-window selection instead.
std::vector<fs::path> relevant_summary_files(
    const std::vector<fs::path> & files, const std::string & query, int max_summaries) {
    const std::string fts_query = sanitize_fts5_query(query);
    if (fts_query.empty() || files.empty()) return {};

    sqlite3 * db = nullptr;
    if (sqlite3_open(":memory:", &db) != SQLITE_OK) {
        sqlite3_close(db);
        return {};
    }
    if (sqlite3_exec(db, "CREATE VIRTUAL TABLE summaries USING fts5(content, annotation)", nullptr, nullptr, nullptr) != SQLITE_OK) {
        sqlite3_close(db); // fts5 module unavailable on this build/device
        return {};
    }

    sqlite3_stmt * insert_stmt = nullptr;
    sqlite3_prepare_v2(db, "INSERT INTO summaries(rowid, content, annotation) VALUES (?, ?, ?)", -1, &insert_stmt, nullptr);
    for (size_t i = 0; i < files.size(); i++) {
        const std::string content = trim(read_file(files[i]));
        const std::string annotation = annotation_for(files[i]);
        sqlite3_bind_int64(insert_stmt, 1, (sqlite3_int64) i); // rowid == index into `files`, for mapping matches back
        sqlite3_bind_text(insert_stmt, 2, content.c_str(), -1, SQLITE_TRANSIENT);
        sqlite3_bind_text(insert_stmt, 3, annotation.c_str(), -1, SQLITE_TRANSIENT);
        sqlite3_step(insert_stmt);
        sqlite3_reset(insert_stmt);
    }
    sqlite3_finalize(insert_stmt);

    std::vector<fs::path> result;
    sqlite3_stmt * query_stmt = nullptr;
    // bm25() ranks best matches with the smallest (most negative) value, so
    // the default ascending ORDER BY already puts the best match first. The
    // two weight arguments correspond to (content, annotation) column order.
    const char * sql =
        "SELECT rowid FROM summaries WHERE summaries MATCH ? ORDER BY bm25(summaries, 1.0, ?) LIMIT ?";
    if (sqlite3_prepare_v2(db, sql, -1, &query_stmt, nullptr) == SQLITE_OK) {
        sqlite3_bind_text(query_stmt, 1, fts_query.c_str(), -1, SQLITE_TRANSIENT);
        sqlite3_bind_double(query_stmt, 2, kAnnotationBm25Weight);
        sqlite3_bind_int(query_stmt, 3, max_summaries > 0 ? max_summaries : -1);
        while (sqlite3_step(query_stmt) == SQLITE_ROW) {
            const size_t idx = (size_t) sqlite3_column_int64(query_stmt, 0);
            if (idx < files.size()) result.push_back(files[idx]);
        }
        sqlite3_finalize(query_stmt);
    }
    sqlite3_close(db);
    return result;
}

} // namespace

char * pc_memory_build_context(const char * memory_dir, const char * query, int max_summaries, size_t max_chars) {
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
            if (entry.is_regular_file() && entry.path().extension() == ".txt" && !is_annotation_file(entry.path())) {
                files.push_back(entry.path());
            }
        }
    }
    // Filenames are zero-padded UTC timestamps (see timestamp_filename), so a
    // lexical sort is also a chronological one.
    std::sort(files.begin(), files.end());

    // FR-013: prefer summaries ranked by FTS5/BM25 relevance to `query` over
    // pure recency, falling back to the original recency-window selection
    // whenever there's no strong match (FTS5 unavailable, no usable query
    // terms, or zero matches) — see relevant_summary_files()'s doc comment.
    std::vector<fs::path> selected;
    if (query && *query) {
        selected = relevant_summary_files(files, query, max_summaries);
    }
    if (selected.empty() && max_summaries > 0 && !files.empty()) {
        const size_t start = files.size() > (size_t) max_summaries ? files.size() - (size_t) max_summaries : 0;
        selected.assign(files.begin() + start, files.end());
    }

    if (!selected.empty()) {
        out << "\nRecent session summaries:\n";
        for (const auto & file : selected) {
            const std::string s = trim(read_file(file));
            if (s.empty()) continue;
            out << "- " << s << "\n";
            // FR-023: shown as a distinctly-labeled line, never merged into
            // the summary text above — this is the user's own voice, not
            // something the model wrote or is being asked to treat as such.
            const std::string note = annotation_for(file);
            if (!note.empty()) out << "  [user note: " << note << "]\n";
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
