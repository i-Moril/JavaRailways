# HTTP editor initialization fix and deployment

## Cause and change

On the deployed HTTP origin `http://mc.art1st.work:29002/`, `isSecureContext` is false and `crypto.randomUUID` is undefined. `defaultProgramStep` called it during toolbox construction, throwing an uncaught TypeError and unmounting the React root. This was reproduced with the previous production bundle.

Commit `981848e14bb0a329cd773f7167d151120b023771` uses `crypto.getRandomValues` to generate 128-bit hex step IDs. Existing step identities and persistence shapes are unchanged. Regression coverage initializes and round-trips every toolbox step with `randomUUID` unavailable. The frontend spec now requires deployment-like HTTP validation.

## Verification

- Frontend: 200 tests / 33 files passed; TypeScript and production build passed.
- `:BukkitPlugin:shadowJar` passed. All 17 frontend files in the nested WebUI JAR matched the build.
- Local production assets served from `http://dev:18882/`: actual browser `isSecureContext=false`, `crypto.randomUUID=undefined`. Library and arrival modal both displayed Blockly canvas, toolbox and entry without page errors. Used isolated fixture APIs and local recovery state, never real player documents.
- After deployment, public HTTP origin served `assets/index-BiXlFvKJ.js`; library displayed one canvas and one toolbox, with no browser errors under the same API limitations. Isolated browser-only fixtures used for privileged endpoints; no real player documents changed.

## Deployment and recovery

Target: JavaRailwaysTest, MCSManager instance `836082a48c4f48fcb1fea4e83c57dc96` on MC / `192.168.1.230`.

- Normal stop: all worlds/global data/player data saved at 09:26:29 UTC, status 0 / PID 0 confirmed.
- Recovery: `/opt/mcsmanager/backups/javarailways/merged-review-20261023-m6j9ydl9` (existing tool retains historical prefix).
- Recovery archive SHA-256: `2201e1cba98efd463cba7633c485070f49e9bd36083a8ec087a719d4127651b9`; 134 plugin data files inventoried.
- Candidate/plan: `/tmp/jr-http-fix-deploy-20261024-981848e1` locally and remotely.
- Replaced only JavaRailways.jar, 66,791,272 bytes. Old SHA-256 `c4bdf0ec44b5431dd88e25ba2853a52b383680ce1236ff14256f4dbaa9704b7b`; new SHA-256 `4deaae617700dda832b99b9650b49856542f458c93d982c536f0c0e489713f79`.
- Install audit at 09:26:59 UTC verifies plugin data byte-identical during replacement and other plugin JARs unchanged.
- Normal start: server Done at 09:27:55 UTC. Bridge health HTTP 200 / ok true; nested/extracted sidecar identical; all 17 served frontend assets and public index matched.
- ATO world RUNNING. SimpleSignals installed: definition revision 46 / topology revision 1, 6 regions/tables/blocks, 2 fixed-block and 2 protection signals.
- Startup also recorded Mojang public-key fetch Connection reset and the existing Terra Unsafe error; neither originated in the changed frontend. Existing HTTP exposure and transient startup physics warnings remain.
- Scope of acceptance: browser initialization and toolbox availability, not complete gameplay execution acceptance.
