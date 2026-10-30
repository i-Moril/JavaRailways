# Waypoint pruning deployment — 2026-10-27

Product commit a277f64cab7cd265ada536e3d3d2283335e4566f. Candidate SHA256 bab7b783e7b1cc179eae1338e2160255d585e9e517097e43608e5a9d956c6d03.

Basic435/SimpleATO634/SimpleSignals656/Bukkit640/WebUI36 tests pass (2401 total, zero failures/errors/skips). Baseline actual old8ae deployedfatjar reproduces exact four preexisting SimpleSignals fixture failures; fixture-only setup corrected without weakening physical/protection/clock/rollback assertions. Independent review passed.

Candidate preflight reads current59rails/31waypoints/26directions/6protectedpath replacements in separateSIMULATION CoreWorld: UP2U/4U both unique withdirectionalpruning+protectionenabled, invalidBA origin still fails. Live Reset verification followsrestart.

Target user@192.168.1.230, instance836082a48c4f48fcb1fea4e83c57dc96. Normalshutdown10:22:59UTC savedallworld/global/playerdata. Fullrecovery /opt/mcsmanager/backups/javarailways/merged-review-20261023-vlfrsu6n, archiveSHA256 08ab4a807b7c00eb0d8feb9a8bc694e08c5437a86c06ddd68c6b075acee84157.

Install10:23:57UTC replacedonlyJavaRailways.jar. All136plugin datafiles unchanged, otherpluginJARs unchanged. No persistence migration, libraryorparkingpointediting. Taskremainsin_progress/unarchived.

## Live acceptance
StartupDone10:24:52UTC. Bridge8765healthy,WebUI29002,17servedassets match embedded candidate;WebUIhash unchanged64672de238016ee939e0f6e71eba6993d6f01f93e6e63bc35a1a53996d3788ae.

Actual live Reset endpoint /worlds/world/lines/line-1/simulate called using originalsavedrunparameters and S100profile:UPto2U200,UPto4U200,DOWNfive-station200. Formerlyfailinglastleg lengths1049.7148951690315m/1050.0301977782992m. No injectedvia,parkingpointedits,profilefakes orgeneratedsuccess. All7editor/libraryfiles stillhashmatch stoppedbackupafterverification. Livegame/playeracceptance remainsseparate.

KnownplaintextHTTPwarnings andtransientstartupPhysics epoch/deltawarnings remain; no claim tofixunrelatedserverwarnings.
