SPDX-License-Identifier: GPL-3.0-or-later

test-localhost.p12: THROWAWAY TEST-ONLY TLS identity for HttpSourceFetcher tests.
Self-signed EC P-256 certificate, CN=localhost, SAN dns:localhost, generated with keytool on 2026-10-13.
Store and key password: quicraft-test-only. Not a secret; never trusted outside these tests;
never used for updater signing (updater signing keys are Ed25519 and owner-managed offline).
