# Decisions

- **Updater belongs in Phase 4:** the user confirmed `docs/.plans/updater-plan.md` is their prewritten roadmap addition and selected implementation with the public alpha. Treat the draft's open decisions as unresolved, not implicit approval of every detail. (src: user, 2026-10-13, verified)
- **Use test4cheap:** the user explicitly requested the test4cheap workflow for testing. Delegate test writing to cheap-tester with precise specifications, isolated mutation checks, independent verification, and peer review. (src: user, 2026-10-13, verified)
- **Updater release sources:** support Modrinth as primary, plus GitHub and `git.sudoe.rs`. Actual project paths and publishing are not yet configured or authorized. (src: user, 2026-10-13, verified)
- **Updater application and defaults:** retain the draft's atomic live jar swap on Linux/macOS, subject to loader safety evidence and review. Retain hourly checks only, first-start disclosure, and one console warning per distinct invalid manifest; automatic jar downloading remains off. (src: user, 2026-10-13, verified)
- **Parallelism and VRAM:** multiple coding agents and regular testers are allowed, but at most one cheap-tester/test4cheap llama.cpp agent may run at once because VRAM is limited. Regular testers still follow the test4cheap quality gates. (src: user, 2026-10-13, verified)
