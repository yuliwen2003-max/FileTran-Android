#include "cimbar_js/cimbar_recv_js.h"
#include "cimb_translator/Config.h"
#include "encoder/Encoder.h"

#include <jni.h>
#include <android/log.h>
#include <opencv2/imgproc/imgproc.hpp>
#include <chrono>
#include <algorithm>
#include <fstream>
#include <filesystem>
#include <sstream>
#include <memory>
#include <mutex>
#include <regex>
#include <set>
#include <string>
#include <thread>
#include <vector>

#define TAG "AirGapDecoderCPP"

namespace {
    std::mutex g_mutex;
    std::set<uint32_t> g_completed_ids;
    std::string g_output_dir;
    unsigned long long g_calls = 0;
    int g_mode = 68;
    int g_auto_locked_mode = 0;
    int g_auto_probe_counter = 0;
    std::vector<unsigned char> g_bufspace;
    constexpr int kAutoModes[] = {68, 67, 66, 4};
    constexpr bool kVerboseLog = false;
    bool g_sender_initialized = false;
    bool g_sender_prepared = false;
    int g_sender_width = 0;
    int g_sender_height = 0;
    int g_sender_mode = 68;
    unsigned long long g_sender_prepare_seq = 0;
    unsigned long long g_sender_frame_seq = 0;
    unsigned long long g_sender_restart_seq = 0;
    int g_sender_last_frame_w = 0;
    int g_sender_last_frame_h = 0;
    uint8_t g_sender_encode_id = 109;
    std::unique_ptr<Encoder> g_sender_encoder;
    fountain_encoder_stream::ptr g_sender_fes;

    std::string jstring_to_std(JNIEnv* env, jstring value) {
        if (!value) return "";
        const char* raw = env->GetStringUTFChars(value, nullptr);
        std::string out(raw ? raw : "");
        env->ReleaseStringUTFChars(value, raw);
        return out;
    }

    void trace_log(const std::string& msg) {
        __android_log_print(ANDROID_LOG_INFO, TAG, "%s", msg.c_str());
        if (g_output_dir.empty()) {
            return;
        }
        std::ofstream out(g_output_dir + "/decoder_trace.log", std::ios::app);
        if (!out.is_open()) {
            return;
        }
        auto now = std::chrono::system_clock::now();
        auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(now.time_since_epoch()).count();
        std::ostringstream line;
        line << ms << " [tid=" << std::this_thread::get_id() << "] " << msg << "\n";
        out << line.str();
        out.flush();
    }

    std::string read_report_text() {
        std::vector<unsigned char> rep(2048, 0);
        const unsigned rsz = cimbard_get_report(rep.data(), static_cast<unsigned>(rep.size()));
        if (rsz == 0) return "";
        return std::string(reinterpret_cast<char*>(rep.data()), reinterpret_cast<char*>(rep.data()) + rsz);
    }

    float extract_progress_0_1(const std::string& report) {
        // libcimbar report is usually like: "[ 0.972799 ]"
        static const std::regex num_re(R"((-?\d+(?:\.\d+)?))");
        std::smatch m;
        if (!std::regex_search(report, m, num_re)) {
            return 0.0f;
        }
        try {
            float p = std::stof(m.str(1));
            if (p < 0.0f) p = 0.0f;
            if (p > 1.0f) p = 1.0f;
            return p;
        } catch (...) {
            return 0.0f;
        }
    }

    void configure_mode_locked(int mode) {
        g_mode = mode;
        cimbard_configure_decode(g_mode);
        g_bufspace.assign(static_cast<size_t>(cimbard_get_bufsize()), 0);
        g_completed_ids.clear();
        trace_log("configured sync mode=" + std::to_string(g_mode) + " bufsize=" + std::to_string(g_bufspace.size()));
    }

}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_yuliwen_filetran_airgap_AirGapSender_nativeSenderInit(
    JNIEnv*,
    jobject
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_sender_initialized = true;
    g_sender_prepared = false;
    g_sender_width = 0;
    g_sender_height = 0;
    g_sender_mode = 68;
    g_sender_prepare_seq = 0;
    g_sender_frame_seq = 0;
    g_sender_restart_seq = 0;
    g_sender_last_frame_w = 0;
    g_sender_last_frame_h = 0;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_yuliwen_filetran_airgap_AirGapSender_nativePrepare(
    JNIEnv* env,
    jobject,
    jstring fileName,
    jbyteArray data,
    jint mode,
    jint compression
) {
    if (!data || !fileName) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_sender_initialized) return JNI_FALSE;

    const std::string name = jstring_to_std(env, fileName);
    if (name.empty()) return JNI_FALSE;

    const jsize total = env->GetArrayLength(data);
    if (total <= 0) return JNI_FALSE;

    jboolean isCopy = JNI_FALSE;
    jbyte* bytes = env->GetByteArrayElements(data, &isCopy);
    if (!bytes) return JNI_FALSE;

    try {
        int modeValue = static_cast<int>(mode);
        if (modeValue <= 0) modeValue = 68;
        g_sender_mode = modeValue;
        cimbar::Config::update(static_cast<unsigned>(modeValue));

        int compressionLevel = static_cast<int>(compression);
        if (compressionLevel < 0 || compressionLevel > 22) {
            compressionLevel = cimbar::Config::compression_level();
        }

        std::stringstream input;
        input.write(reinterpret_cast<const char*>(bytes), static_cast<std::streamsize>(total));
        if (!input.good()) {
            env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
            return JNI_FALSE;
        }

        g_sender_encoder = std::make_unique<Encoder>();
        g_sender_encode_id = static_cast<uint8_t>((g_sender_encode_id + 1) & 0x7F);
        g_sender_encoder->set_encode_id(g_sender_encode_id);
        g_sender_fes = g_sender_encoder->create_fountain_encoder(input, name, compressionLevel);
        if (!g_sender_fes || !g_sender_fes->good()) {
            env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
            g_sender_encoder.reset();
            g_sender_fes.reset();
            return JNI_FALSE;
        }

        g_sender_width = cimbar::Config::image_size_x();
        g_sender_height = cimbar::Config::image_size_y();
        g_sender_prepared = true;
        g_sender_prepare_seq++;
        g_sender_frame_seq = 0;
        g_sender_restart_seq = 0;
        g_sender_last_frame_w = 0;
        g_sender_last_frame_h = 0;
        trace_log(
            "sender prepare ok seq=" + std::to_string(g_sender_prepare_seq) +
            " mode=" + std::to_string(g_sender_mode) +
            " file=" + name +
            " bytes=" + std::to_string(total) +
            " frame=" + std::to_string(g_sender_width) + "x" + std::to_string(g_sender_height) +
            " canvas_locked=1"
        );
    } catch (...) {
        env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
        return JNI_FALSE;
    }

    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_yuliwen_filetran_airgap_AirGapSender_nativeNextFrame(
    JNIEnv* env,
    jobject
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_sender_initialized || !g_sender_prepared || !g_sender_encoder || !g_sender_fes) {
        return env->NewIntArray(0);
    }
    // Config is thread_local in libcimbar; nextFrame may hop threads from coroutine pool.
    // Re-apply sender mode on every frame to keep output size/mode stable.
    cimbar::Config::update(g_sender_mode);

    const unsigned required = g_sender_fes->blocks_required() * 8;
    if (required > 0 && g_sender_fes->block_count() >= required) {
        g_sender_fes->restart();
        g_sender_restart_seq++;
        trace_log(
            "sender restart seq=" + std::to_string(g_sender_restart_seq) +
            " mode=" + std::to_string(g_sender_mode) +
            " required=" + std::to_string(required)
        );
    }

    cimbar::vec_xy senderCanvas{};
    senderCanvas.x = static_cast<unsigned>(std::max(0, g_sender_width));
    senderCanvas.y = static_cast<unsigned>(std::max(0, g_sender_height));
    auto frameOpt = g_sender_encoder->encode_next(*g_sender_fes, senderCanvas);
    if (!frameOpt.has_value()) {
        return env->NewIntArray(0);
    }
    cv::Mat frame = frameOpt.value();
    if (frame.empty()) {
        return env->NewIntArray(0);
    }
    cv::Mat rgba;
    if (frame.channels() == 3) {
        cv::cvtColor(frame, rgba, cv::COLOR_BGR2RGBA);
    } else if (frame.channels() == 4) {
        cv::cvtColor(frame, rgba, cv::COLOR_BGRA2RGBA);
    } else if (frame.channels() == 1) {
        cv::cvtColor(frame, rgba, cv::COLOR_GRAY2RGBA);
    } else {
        return env->NewIntArray(0);
    }

    const int width = rgba.cols;
    const int height = rgba.rows;
    const int pixels = width * height;
    if (width <= 0 || height <= 0 || pixels <= 0) {
        return env->NewIntArray(0);
    }

    g_sender_frame_seq++;
    if (width != g_sender_last_frame_w || height != g_sender_last_frame_h) {
        trace_log(
            "sender frame size change seq=" + std::to_string(g_sender_frame_seq) +
            " mode=" + std::to_string(g_sender_mode) +
            " from=" + std::to_string(g_sender_last_frame_w) + "x" + std::to_string(g_sender_last_frame_h) +
            " to=" + std::to_string(width) + "x" + std::to_string(height)
        );
        g_sender_last_frame_w = width;
        g_sender_last_frame_h = height;
    } else if ((g_sender_frame_seq % 120ULL) == 1ULL) {
        trace_log(
            "sender frame tick seq=" + std::to_string(g_sender_frame_seq) +
            " mode=" + std::to_string(g_sender_mode) +
            " size=" + std::to_string(width) + "x" + std::to_string(height)
        );
    }

    std::vector<jint> out(static_cast<size_t>(2 + pixels), 0);
    out[0] = width;
    out[1] = height;

    for (int y = 0; y < height; ++y) {
        const unsigned char* row = rgba.ptr<unsigned char>(y);
        for (int x = 0; x < width; ++x) {
            const int p = x * 4;
            const unsigned int r = row[p + 0];
            const unsigned int g = row[p + 1];
            const unsigned int b = row[p + 2];
            const unsigned int a = row[p + 3];
            out[2 + (y * width + x)] = static_cast<jint>((a << 24) | (r << 16) | (g << 8) | b);
        }
    }

    jintArray arr = env->NewIntArray(static_cast<jsize>(out.size()));
    if (!arr) return env->NewIntArray(0);
    env->SetIntArrayRegion(arr, 0, static_cast<jsize>(out.size()), out.data());
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_yuliwen_filetran_airgap_AirGapSender_nativeSenderShutdown(
    JNIEnv*,
    jobject
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_sender_prepared = false;
    g_sender_initialized = false;
    g_sender_width = 0;
    g_sender_height = 0;
    g_sender_mode = 68;
    g_sender_frame_seq = 0;
    g_sender_restart_seq = 0;
    g_sender_last_frame_w = 0;
    g_sender_last_frame_h = 0;
    g_sender_encoder.reset();
    g_sender_fes.reset();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_yuliwen_filetran_airgap_AirGapDecoder_nativeInit(
    JNIEnv* env,
    jobject,
    jstring outputDir
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_output_dir = jstring_to_std(env, outputDir);
    g_completed_ids.clear();
    g_calls = 0;
    g_auto_locked_mode = 0;
    g_auto_probe_counter = 0;
    if (g_output_dir.empty()) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "output dir is empty");
        return JNI_FALSE;
    }
    std::filesystem::create_directories(g_output_dir);
    std::ofstream clear(g_output_dir + "/decoder_trace.log", std::ios::trunc);
    clear.close();
    trace_log("nativeInit begin");
    configure_mode_locked(68);
    trace_log("sync decoder bufsize=" + std::to_string(g_bufspace.size()));
    trace_log("nativeInit ok dir=" + g_output_dir);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_yuliwen_filetran_airgap_AirGapDecoder_processImageJNI(
    JNIEnv* env,
    jobject,
    jbyteArray frameData,
    jint width,
    jint height,
    jint channels,
    jint modeVal
) {
    ++g_calls;
    int mode = static_cast<int>(modeVal);
    const bool autoModeRequested = mode <= 0;
    if (autoModeRequested) {
        mode = g_auto_locked_mode > 0 ? g_auto_locked_mode : 68;
    } else if (mode <= 0) {
        mode = 68;
    }
    if (kVerboseLog || (g_calls % 240ULL) == 1ULL) {
        std::ostringstream ss;
        ss << "processImage enter call=" << g_calls
           << " modeVal=" << static_cast<int>(modeVal)
           << " auto=" << (autoModeRequested ? 1 : 0)
           << " g_mode=" << g_mode
           << " mode=" << mode
           << " w=" << width
           << " h=" << height
           << " ch=" << channels;
        trace_log(ss.str());
    }
    if (!frameData || width <= 0 || height <= 0) {
        trace_log("processImage invalid params or proc");
        return env->NewStringUTF("");
    }

    jsize len = env->GetArrayLength(frameData);
    int pixelCount = width * height;
    int expected = channels == 4 ? pixelCount * 4 : pixelCount;
    if (len < expected) {
        trace_log("processImage skip: len<expected");
        return env->NewStringUTF("");
    }

    jboolean isCopy = JNI_FALSE;
    jbyte* bytes = env->GetByteArrayElements(frameData, &isCopy);
    if (!bytes) {
        trace_log("processImage skip: GetByteArrayElements null");
        return env->NewStringUTF("");
    }

    std::string result;
    try {
        std::lock_guard<std::mutex> lock(g_mutex);
        if (g_output_dir.empty() || g_bufspace.empty()) {
            trace_log("processImage skip: not initialized");
            env->ReleaseByteArrayElements(frameData, bytes, JNI_ABORT);
            return env->NewStringUTF("");
        }
        if (!autoModeRequested) {
            g_auto_locked_mode = 0;
            g_auto_probe_counter = 0;
        }
        if (mode != g_mode) {
            if (kVerboseLog) {
                trace_log("reconfigure reason=mode_change prev=" + std::to_string(g_mode) + " next=" + std::to_string(mode));
            }
            configure_mode_locked(mode);
        }

        cv::Mat frameMat;
        if (channels == 4) {
            // Align with CFC: pass camera RGBA frame directly into decoder pipeline.
            cv::Mat rgba(height, width, CV_8UC4, reinterpret_cast<unsigned char*>(bytes));
            frameMat = rgba;
        } else {
            cv::Mat gray(height, width, CV_8UC1, reinterpret_cast<unsigned char*>(bytes));
            cv::cvtColor(gray, frameMat, cv::COLOR_GRAY2RGBA);
        }

        // Hot path optimization: decode directly from current frame buffer to reduce per-frame copy.
        if (kVerboseLog || (g_calls % 240ULL) == 1ULL) {
            trace_log("prepared frame " + std::to_string(frameMat.cols) + "x" + std::to_string(frameMat.rows) + " -> " +
                      std::to_string(frameMat.cols) + "x" + std::to_string(frameMat.rows));
        }

        cv::Mat bgr;
        cv::cvtColor(frameMat, bgr, cv::COLOR_RGBA2BGR);
        int bytesOut = 0;
        if (autoModeRequested && g_auto_locked_mode == 0) {
            g_auto_probe_counter++;
            const bool shouldProbeAllModes = (g_auto_probe_counter % 10) == 1;
            if (!shouldProbeAllModes) {
                bytesOut = cimbard_scan_extract_decode(
                    bgr.data,
                    static_cast<unsigned>(bgr.cols),
                    static_cast<unsigned>(bgr.rows),
                    3,
                    g_bufspace.data(),
                    static_cast<unsigned>(g_bufspace.size())
                );
            } else {
            int bestMode = 0;
            int bestBytes = 0;
            std::vector<unsigned char> bestBuf;
            for (const int probeMode : kAutoModes) {
                cimbard_configure_decode(probeMode);
                std::vector<unsigned char> probeBuf(static_cast<size_t>(cimbard_get_bufsize()), 0);
                const int probeBytes = cimbard_scan_extract_decode(
                    bgr.data,
                    static_cast<unsigned>(bgr.cols),
                    static_cast<unsigned>(bgr.rows),
                    3,
                    probeBuf.data(),
                    static_cast<unsigned>(probeBuf.size())
                );
                if (probeBytes > bestBytes) {
                    bestBytes = probeBytes;
                    bestMode = probeMode;
                    bestBuf = std::move(probeBuf);
                }
            }
            if (bestMode > 0 && bestBytes > 0) {
                g_auto_locked_mode = bestMode;
                g_auto_probe_counter = 0;
                configure_mode_locked(g_auto_locked_mode);
                if (g_bufspace.size() >= bestBuf.size()) {
                    std::copy(bestBuf.begin(), bestBuf.end(), g_bufspace.begin());
                } else {
                    g_bufspace = bestBuf;
                }
                bytesOut = bestBytes;
                result = "/" + std::to_string(g_auto_locked_mode);
                if (kVerboseLog || (g_calls % 240ULL) == 1ULL) {
                    trace_log("auto mode locked=" + std::to_string(g_auto_locked_mode) + " bytes=" + std::to_string(bestBytes));
                }
            } else {
                if (kVerboseLog) {
                    trace_log("auto probe no lock, fallback mode=68");
                }
                configure_mode_locked(68);
            }
            }
        } else {
            bytesOut = cimbard_scan_extract_decode(
                bgr.data,
                static_cast<unsigned>(bgr.cols),
                static_cast<unsigned>(bgr.rows),
                3,
                g_bufspace.data(),
                static_cast<unsigned>(g_bufspace.size())
            );
        }
        if (kVerboseLog || (g_calls % 240ULL) == 1ULL) {
            trace_log("scan_extract bytes=" + std::to_string(bytesOut) + " via=orig/rgba->bgr");
        }
        if (bytesOut <= 0) {
            if (kVerboseLog) {
                trace_log("scan_extract <=0, return empty");
            }
            env->ReleaseByteArrayElements(frameData, bytes, JNI_ABORT);
            return env->NewStringUTF("");
        }

        if (!result.empty() && result[0] == '/') {
            env->ReleaseByteArrayElements(frameData, bytes, JNI_ABORT);
            return env->NewStringUTF(result.c_str());
        }

        const int64_t decRes = cimbard_fountain_decode(g_bufspace.data(), static_cast<unsigned>(bytesOut));
        const std::string reportText = read_report_text();
        if (kVerboseLog || (g_calls % 240ULL) == 1ULL) {
            trace_log("fountain decode res=" + std::to_string(decRes));
            if (!reportText.empty()) {
                trace_log("fountain report=" + reportText);
            }
        }
        if (decRes <= 0) {
            const float progress = extract_progress_0_1(reportText);
            std::ostringstream progressSignal;
            progressSignal << "@PROGRESS|" << progress;
            result = progressSignal.str();
            if (kVerboseLog) {
                trace_log("return progress signal=" + result);
            }
            env->ReleaseByteArrayElements(frameData, bytes, JNI_ABORT);
            return env->NewStringUTF(result.c_str());
        }

        const uint32_t fileId = static_cast<uint32_t>(decRes);
        if (!g_completed_ids.insert(fileId).second) {
            env->ReleaseByteArrayElements(frameData, bytes, JNI_ABORT);
            return env->NewStringUTF("");
        }

        std::string filename(255, '\0');
        const int fnsize = cimbard_get_filename(fileId, filename.data(), static_cast<unsigned>(filename.size()));
        if (fnsize > 0) {
            filename.resize(static_cast<size_t>(fnsize));
        } else {
            filename = "recv_" + std::to_string(fileId) + ".bin";
        }
        const unsigned fileSize = cimbard_get_filesize(fileId);

        const std::string filepath = g_output_dir + "/" + filename;
        std::ofstream outs(filepath, std::ios::binary);
        if (!outs.is_open()) {
            trace_log("failed to open output file: " + filepath);
            env->ReleaseByteArrayElements(frameData, bytes, JNI_ABORT);
            return env->NewStringUTF("");
        }

        std::vector<unsigned char> decbuf(static_cast<size_t>(cimbard_get_decompress_bufsize()));
        int readRes = 1;
        while ((readRes = cimbard_decompress_read(fileId, decbuf.data(), static_cast<unsigned>(decbuf.size()))) > 0) {
            outs.write(reinterpret_cast<const char*>(decbuf.data()), readRes);
        }
        outs.flush();
        outs.close();

        if (readRes < 0) {
            trace_log("decompress_read error=" + std::to_string(readRes));
            env->ReleaseByteArrayElements(frameData, bytes, JNI_ABORT);
            return env->NewStringUTF("");
        }
        result = "@FILE|" + filepath + "|" + filename + "|" + std::to_string(fileSize);
        trace_log("new decoded file id=" + std::to_string(fileId) + " path=" + result);
    } catch (const cv::Exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "opencv exception: %s", e.what());
        trace_log(std::string("opencv exception: ") + e.what());
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "std exception: %s", e.what());
        trace_log(std::string("std exception: ") + e.what());
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "unknown native exception in processImageJNI");
        trace_log("unknown native exception in processImageJNI");
    }

    env->ReleaseByteArrayElements(frameData, bytes, JNI_ABORT);

    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_yuliwen_filetran_airgap_AirGapDecoder_shutdownJNI(
    JNIEnv*,
    jobject
) {
    std::lock_guard<std::mutex> lock(g_mutex);
    trace_log("shutdown begin");
    g_completed_ids.clear();
    g_bufspace.clear();
    trace_log("shutdown done");
}





