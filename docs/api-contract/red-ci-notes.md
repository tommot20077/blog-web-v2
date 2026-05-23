# Contract Red CI Notes

The contract red suite is intentionally non-blocking until Yuan promotes it.

Recommended job behavior:

- Run `.\docs\api-contract\scripts\run-red-audit.ps1`.
- Upload `logs\contract-red-audit.log`.
- Upload `logs\api-contract-red\`.
- Do not fail blocking CI because these tests are allowed to be red in Phase 1.

Promotion rule:

- Only move this into blocking CI after the team decides the contract red checks should be made green.
