use jni::objects::{JClass, JString};
use jni::sys::jint;
use jni::sys::jstring;
use jni::JNIEnv;
use once_cell::sync::Lazy;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::RwLock;
use std::time::{SystemTime, UNIX_EPOCH};

const ANDROID_VR_USER_AGENT: &str =
    "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/132.0.6808.3)";

#[derive(Clone, Serialize, Deserialize, Debug)]
pub struct ResolutionResult {
    pub success: bool,
    pub video_url: Option<String>,
    pub audio_url: Option<String>,
    pub is_muxed: bool,
    pub height: i32,
    pub expires_in: i64,
    pub error: Option<String>,
}

#[derive(Clone)]
struct CacheEntry {
    result: ResolutionResult,
    expires_at_ms: u64,
}

static CACHE: Lazy<RwLock<HashMap<String, CacheEntry>>> =
    Lazy::new(|| RwLock::new(HashMap::new()));

fn current_time_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

fn follow_redirects_for_stream(initial_url: &str) -> String {
    let mut current_url = initial_url.to_string();
    let client = ureq::AgentBuilder::new()
        .timeout(std::time::Duration::from_secs(4))
        .redirects(0) // We follow manually to capture Location
        .build();

    for _ in 0..4 {
        let resp = match client
            .head(&current_url)
            .set("User-Agent", ANDROID_VR_USER_AGENT)
            .call()
        {
            Ok(r) => r,
            Err(ureq::Error::Status(302, r)) | Err(ureq::Error::Status(303, r)) => r,
            Err(_) => break,
        };

        if let Some(loc) = resp.header("location") {
            current_url = loc.to_string();
        } else {
            break;
        }
    }

    current_url
}

pub fn resolve_youtube_video(video_id: &str, max_height: i32) -> ResolutionResult {
    let cache_key = format!("{}:{}", video_id, max_height);
    let now = current_time_ms();

    // Check fast cache
    if let Ok(cache) = CACHE.read() {
        if let Some(entry) = cache.get(&cache_key) {
            if entry.expires_at_ms > now + 60_000 {
                return entry.result.clone();
            }
        }
    }

    let payload = serde_json::json!({
        "context": {
            "client": {
                "clientName": "ANDROID_VR",
                "clientVersion": "1.61.48",
                "clientId": "28",
                "osName": "Android",
                "osVersion": "12",
                "androidSdkVersion": 32,
                "gl": "US",
                "hl": "en"
            }
        },
        "videoId": video_id,
        "playbackContext": {
            "contentPlaybackContext": {
                "html5Preference": "HTML5_PREF_WANTS"
            }
        }
    });

    let client = ureq::AgentBuilder::new()
        .timeout(std::time::Duration::from_secs(6))
        .build();

    let resp_res = client
        .post("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
        .set("Content-Type", "application/json")
        .set("User-Agent", ANDROID_VR_USER_AGENT)
        .send_json(&payload);

    let resp = match resp_res {
        Ok(r) => r,
        Err(e) => {
            return ResolutionResult {
                success: false,
                video_url: None,
                audio_url: None,
                is_muxed: false,
                height: 0,
                expires_in: 0,
                error: Some(format!("HTTP error: {}", e)),
            };
        }
    };

    let json_val: serde_json::Value = match resp.into_json() {
        Ok(v) => v,
        Err(e) => {
            return ResolutionResult {
                success: false,
                video_url: None,
                audio_url: None,
                is_muxed: false,
                height: 0,
                expires_in: 0,
                error: Some(format!("JSON parse error: {}", e)),
            };
        }
    };

    let status = json_val
        .pointer("/playabilityStatus/status")
        .and_then(|v| v.as_str())
        .unwrap_or("UNKNOWN");

    if status != "OK" {
        let reason = json_val
            .pointer("/playabilityStatus/reason")
            .and_then(|v| v.as_str())
            .unwrap_or("Video unplayable");
        return ResolutionResult {
            success: false,
            video_url: None,
            audio_url: None,
            is_muxed: false,
            height: 0,
            expires_in: 0,
            error: Some(format!("{}: {}", status, reason)),
        };
    }

    let streaming_data = match json_val.get("streamingData") {
        Some(s) => s,
        None => {
            return ResolutionResult {
                success: false,
                video_url: None,
                audio_url: None,
                is_muxed: false,
                height: 0,
                expires_in: 0,
                error: Some("No streamingData".to_string()),
            };
        }
    };

    let expires_in = streaming_data
        .get("expiresInSeconds")
        .and_then(|v| v.as_str().and_then(|s| s.parse::<i64>().ok()).or_else(|| v.as_i64()))
        .unwrap_or(21600);

    // 1. Try muxed format
    if let Some(formats) = streaming_data.get("formats").and_then(|v| v.as_array()) {
        let mut candidates: Vec<&serde_json::Value> = formats
            .iter()
            .filter(|f| {
                let h = f.get("height").and_then(|h| h.as_i64()).unwrap_or(0) as i32;
                let has_url = f.get("url").and_then(|u| u.as_str()).is_some();
                has_url && h > 0 && h <= max_height
            })
            .collect();

        candidates.sort_by(|a, b| {
            let ha = a.get("height").and_then(|h| h.as_i64()).unwrap_or(0);
            let hb = b.get("height").and_then(|h| h.as_i64()).unwrap_or(0);
            let ba = a.get("bitrate").and_then(|b| b.as_i64()).unwrap_or(0);
            let bb = b.get("bitrate").and_then(|b| b.as_i64()).unwrap_or(0);
            hb.cmp(&ha).then(bb.cmp(&ba))
        });

        if let Some(best) = candidates.first() {
            if let Some(raw_url) = best.get("url").and_then(|u| u.as_str()) {
                let h = best.get("height").and_then(|h| h.as_i64()).unwrap_or(360) as i32;
                let final_url = follow_redirects_for_stream(raw_url);
                let res = ResolutionResult {
                    success: true,
                    video_url: Some(final_url),
                    audio_url: None,
                    is_muxed: true,
                    height: h,
                    expires_in,
                    error: None,
                };

                if let Ok(mut cache) = CACHE.write() {
                    cache.insert(
                        cache_key,
                        CacheEntry {
                            result: res.clone(),
                            expires_at_ms: now + (expires_in as u64 * 1000),
                        },
                    );
                }

                return res;
            }
        }
    }

    // 2. Fallback to adaptive video + adaptive audio
    if let Some(adaptives) = streaming_data.get("adaptiveFormats").and_then(|v| v.as_array()) {
        let mut video_candidates: Vec<&serde_json::Value> = adaptives
            .iter()
            .filter(|f| {
                let h = f.get("height").and_then(|h| h.as_i64()).unwrap_or(0) as i32;
                let mime = f.get("mimeType").and_then(|m| m.as_str()).unwrap_or("");
                let has_url = f.get("url").and_then(|u| u.as_str()).is_some();
                has_url && h > 0 && h <= max_height && mime.starts_with("video/")
            })
            .collect();

        video_candidates.sort_by(|a, b| {
            let ha = a.get("height").and_then(|h| h.as_i64()).unwrap_or(0);
            let hb = b.get("height").and_then(|h| h.as_i64()).unwrap_or(0);
            let ba = a.get("bitrate").and_then(|b| b.as_i64()).unwrap_or(0);
            let bb = b.get("bitrate").and_then(|b| b.as_i64()).unwrap_or(0);
            hb.cmp(&ha).then(bb.cmp(&ba))
        });

        let mut audio_candidates: Vec<&serde_json::Value> = adaptives
            .iter()
            .filter(|f| {
                let mime = f.get("mimeType").and_then(|m| m.as_str()).unwrap_or("");
                let has_url = f.get("url").and_then(|u| u.as_str()).is_some();
                has_url && mime.starts_with("audio/")
            })
            .collect();

        audio_candidates.sort_by(|a, b| {
            let ba = a.get("bitrate").and_then(|b| b.as_i64()).unwrap_or(0);
            let bb = b.get("bitrate").and_then(|b| b.as_i64()).unwrap_or(0);
            bb.cmp(&ba)
        });

        if let (Some(best_v), Some(best_a)) = (video_candidates.first(), audio_candidates.first()) {
            if let (Some(v_url), Some(a_url)) = (
                best_v.get("url").and_then(|u| u.as_str()),
                best_a.get("url").and_then(|u| u.as_str()),
            ) {
                let h = best_v.get("height").and_then(|h| h.as_i64()).unwrap_or(720) as i32;
                let final_v_url = follow_redirects_for_stream(v_url);
                let final_a_url = follow_redirects_for_stream(a_url);

                let res = ResolutionResult {
                    success: true,
                    video_url: Some(final_v_url),
                    audio_url: Some(final_a_url),
                    is_muxed: false,
                    height: h,
                    expires_in,
                    error: None,
                };

                if let Ok(mut cache) = CACHE.write() {
                    cache.insert(
                        cache_key,
                        CacheEntry {
                            result: res.clone(),
                            expires_at_ms: now + (expires_in as u64 * 1000),
                        },
                    );
                }

                return res;
            }
        }
    }

    ResolutionResult {
        success: false,
        video_url: None,
        audio_url: None,
        is_muxed: false,
        height: 0,
        expires_in: 0,
        error: Some("No suitable video format found".to_string()),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_dezzy_music_utils_NativeVideoResolver_resolveVideoNative<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    j_video_id: JString<'local>,
    max_height: jint,
) -> jstring {
    let video_id: String = match env.get_string(&j_video_id) {
        Ok(s) => s.into(),
        Err(_) => String::new(),
    };

    let result = resolve_youtube_video(&video_id, max_height as i32);
    let json_str = serde_json::to_string(&result).unwrap_or_else(|_| "{}".to_string());

    let output = env.new_string(json_str).expect("Couldn't create java string");
    output.into_raw()
}
