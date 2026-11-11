# Duty timeline deployment — 2026-10-27

Product commit8ae62d563dee6254574b652a288dce6cae8d6182; migration tool corrections38f8bb54 and39ea6890. Main candidateSHA256 adc38fcfa684defa15b03ed2267bcc5aa89a5b9c3fdb295b3a361c2bd4c075b7. Embedded/managedWebUI SHA25664672de238016ee939e0f6e71eba6993d6f01f93e6e63bc35a1a53996d3788ae.

Target MC/user@192.168.1.230, instance836082a48c4f48fcb1fea4e83c57dc96. Normalstop completed09:24:28UTC with allworld/global/player saves. Recovery /opt/mcsmanager/backups/javarailways/merged-review-20261023-y2c52jpo, fullarchiveSHA256 fa9d65a2a8e50c4ca3355a1dd49c78174771e79e31c5fe5db1c482e2ffd57257.

Offline tool preserves independent repository filename and documentinternalID (DraftRepository adds collision suffix without rewriting internalID). Precompiled release17 helper was used on JRE-only host, hashpinned with candidateJAR. JVMguard checks executablebasename, not arbitrary java substring inPythonarguments. 14migrationtests pass.

Dryrun7files:6editor docs+1library;1rewrite (PLACE_INITIAL→duty.initialPosition), no generatedlibraryentries. Fullplugin data136files matches backup+exactplan; allpluginJARs matchinstallmanifest, otherplugins unchanged; currentJava validators pass and reinspection is idempotent. Migrationbeforefiles and hashplan in recovery/timeline-data. Runtime compatibility paths were not added.

Startup Done09:28:59UTC,bridge8765healthok,managedWebUI29002. All17embeddedresources match bothloopback and public http://mc.art1st.work:29002/. No player gameplay acceptance claimed. KnownplaintextHTTPwarnings and startuptransientphysicswarning remain. Task keptin_progress/unarchived forplayerverification.
