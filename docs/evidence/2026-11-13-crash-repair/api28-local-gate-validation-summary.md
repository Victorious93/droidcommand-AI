Emulator validation for commit `14a32934502dcffb794f36d63b19db4c27589d82` (x86_64, API 28):
- PASS: 6 passed, 0 failed, 0 ignored, final code -1
  - `ai.droidcommand.app.MainActivityLaunchTest#launchesToResumedWithoutCrashing`
  - `ai.droidcommand.app.StartupResilienceTest#survivesCorruptPersistedState`
  - `ai.droidcommand.app.StartupResilienceTest#noTrustHintCoversTheTabBarWithoutVictorLaunch`
  - `ai.droidcommand.app.StartupResilienceTest#everyPrimaryTabOpensAndStaysUsable`
  - `ai.droidcommand.app.StartupResilienceTest#survivesRecreationAndBackgroundResume`
  - `ai.droidcommand.app.StartupResilienceTest#repeatedActivityLaunchesStayAlive`
- Cold-start fault matrix, 2 cold starts per scenario: 22 passed, 0 failed (scenarios: no companion, 7 VictorLaunch-theme provider fault shapes, 3 VictorSuite My PC provider fault shapes).
