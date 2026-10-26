# Layerbit Studio working rules

- Work only in this repository. Sheaf and IPO-RADAR are references and must not be modified.
- Preserve the offline manifest removals and scoped file access. No ads, analytics, accounts, cloud fallback or paywalls.
- Original provider inputs are read-only. The engine receives private copies; only export writes user-created destinations.
- Keep image processing cancellable and within the documented pixel budget. Do not silently remove the output-dimension preview.
- All visible tools must work. Deferred neural/advanced features belong in docs, not as inactive buttons.
- Change code on a review branch. Run meaningful pipeline tests, Android lint/build, privacy manifest verification and relevant device tests. Report checks that could not run.
- Do not add a source redistribution license without the owner's choice. Verify new third-party/model licenses before adding them.
