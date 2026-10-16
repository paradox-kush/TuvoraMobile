# Upstream sync — 2026-10-16

This merge starts from Tuvora `org/main` and preserves the released fork history. Existing feature branches and uncommitted primary-checkout work are untouched.

## Integration decisions

- Keep switchable Tuvora backend identity, the single-flight Supabase client, and the single auth refresher. Port the upstream backend rate-limit coordinator and realtime reconnect socket through that provider.
- Preserve setup-code deep links alongside upstream party links, Tuvora branding, build workflows, credentials privacy, feature gates, and the architecture baseline.
- Watch Party remains disabled: neither the Tuvora backend nor the public `NuvioMedia/self-host` snapshot fetched on 2026-10-16 contains `watch_party_*` RPCs or migrations. No backend schema or dashboard/account changes are needed while that capability remains disabled. Enabling it requires a backend implementation and TV playback integration.
- Keep rejecting custom-poster patterns without placeholders. Upstream's token-cache optimization accidentally changed that existing contract; TV already rejects them and the KMP twins now agree.

## Source

`origin/cmp-rewrite` at `229c083cf3db7bab6fda284484f87439eb4e0eb0`; 98 upstream commits absent from the pre-merge Tuvora main.

## iOS and Android adapters

Keep the patched MPVKit revision and existing iOS PiP/render-thread/rotation guards. Port upstream call-aware audio session behavior and the PiP placeholder into that existing controller; omit the competing upstream PiP controller. Swift syntax is checked separately from the Kotlin simulator compilation. Adapt the Android subtitle MP4 parser to Media3 1.11's additional argument.
