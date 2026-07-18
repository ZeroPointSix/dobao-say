# Quick recon notes

## Confirmed
- Package: `com.bytedance.android.doubaoime`
- Likely versionName: `4.2.243.8-doubao`
- Build stamp: `2b0b44c_20260714_124829_...` via slardar.properties (2026-07-14)
- Publisher/signer org: 北京春田知韵科技有限公司
- ABI: arm64-v8a only
- Domain: https://ime.doubao.com

## Interesting native names
- keyboard / ime_net_sdk / ime_ui_android_platform / oime-config
- shell (packers?)
- metasec_ml (security)
- onnxruntime + bytenn (on-device ML)
- sscronet (network)

## First questions for next session
1. Is the Java layer packed by libshell?
2. Where does ImeService bind to native keyboard engine?
3. How are assets/dict/* loaded and verified?
4. What TLS / request signing does ime_net_sdk use?
