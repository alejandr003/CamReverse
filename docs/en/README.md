# CamRevert documentation

> 🌐 **English** · [Español](../README.md)

| Document | For whom | Content |
|---|---|---|
| [`../../README.en.md`](../../README.en.md) | Everyone | What it is, which cameras it works with, installation, usage, troubleshooting, credits and licence. |
| [`CONFIGURATION.md`](CONFIGURATION.md) | Anyone installing the app | **Every key explained** (what `YOUREMAIL`, `PASSWORD`, `CAMPASSWORD`, `VUID` are…), `secrets.properties` vs `.env` vs the Settings dialog, and how to obtain the P2P ID and the `DAS-` address. |
| [`HOW-IT-WORKS.md`](HOW-IT-WORKS.md) | Anyone who wants to understand the project | Architecture (RTSP + P2P tunnel), protocols, session authorisation, the command table and **the reverse-engineering method**: which tools were used, what was read (binaries, logs, captures) and what was tested. |
| [`COMMANDS.md`](COMMANDS.md) | Anyone working on camera control | The full CGI command reference: what each command does, what the camera really answers, and which ones are useless on this firmware. |
| [`PTZ-FINDINGS.md`](PTZ-FINDINGS.md) | Anyone debugging PTZ | The investigation notebook: the symptom, how the official app was intercepted, the root cause and its proof on real hardware. |
| [`../../AGENTS.md`](../../AGENTS.md) | Development | Internal technical specification: memory addresses, SDK disassemblies, error tables and design decisions (Spanish). |

## Shortcuts

- **"Video works but PTZ does not answer"** → the Eye4 account is missing:
  [`CONFIGURATION.md` §3](CONFIGURATION.md#3-why-is-youremail--password-needed).
- **"I do not know where to get the P2P ID or the `DAS-` address"** →
  [`CONFIGURATION.md` §4](CONFIGURATION.md#4-how-to-obtain-the-p2p-id-and-the-das--address).
- **"Why does it reuse the manufacturer's binaries?"** →
  [`HOW-IT-WORKS.md` §2](HOW-IT-WORKS.md#2-the-native-sdk-why-we-reuse-manufacturer-binaries).
- **"What is `eye4_authentication.cgi`?"** →
  [`HOW-IT-WORKS.md` §5](HOW-IT-WORKS.md#5-session-authorisation-eye4_authenticationcgi).
