# Feedback round 2 deployment — 2026-10-27

- Source: abd8f9aa682dd938abde996fa8244cf8afa7f87b.
- Main JAR SHA256: 74ba5f9042a27407b6c3ffe2cd314e18b3f6d018e588068f3ff8b44d5c0fabba.
- WebUI SHA256: 8d2918d0cb8b950e2f1ae718f7f34365894fcc4a223ba27ee36026ba407a716b.
- Normal stop saved all worlds, global data and player data by 06:56:07 UTC; confirmed status0/pid0.
- Recovery: /opt/mcsmanager/backups/javarailways/merged-review-20261023-506cevta.
- Recovery archive SHA256: af10afe61b0e54de2dbe1fb29b5627872fa17132562c3b1f6aa7962e7fc85193, 135 plugin-data files.
- Install: 06:56:46 UTC, plugin data and other JAR hashes unchanged. No migration needed; no library entries removed by deployment.
- Server ready: 06:57:19 UTC. Bridge health ok; nested/extracted WebUI match; all17 static assets matched; public HTML/JS/CSS matched candidate bytes (index-C4DVTu8t.js, index-GM07XQUN.css).
- Existing Terra Unsafe startup error, plaintext HTTP warnings and transient startup physics overload warnings remain.
- Full frontend234/backend37 tests passed. Real HTTP browser file creation, rename preserving invalid draft, explicit discard, delete404, save/reload,409 conflict and refresh verified using isolated QA data.
- User live acceptance pending. Task remains in_progress and unarchived.
