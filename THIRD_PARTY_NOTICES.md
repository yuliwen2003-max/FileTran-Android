# Third-Party Notices

FileTran uses the following open-source projects. When distributing this app, keep the relevant license texts and copyright notices.

## Core Components

1. `libcimbar`
- Upstream: https://github.com/sz3/libcimbar
- License: MPL-2.0
- Local license: `airgap/src/main/cpp/cfc/libcimbar/LICENSE`
- Packaged license: `app/src/main/assets/licenses/libcimbar-MPL-2.0.txt`

2. `cfc`
- Upstream: https://github.com/sz3/cfc
- License: MIT
- Local license snapshot: `_upstream/cfc-master/LICENSE`
- Packaged license: `app/src/main/assets/licenses/cfc-MIT.txt`

3. `cimbar`
- Upstream: https://github.com/sz3/cimbar
- License: MIT
- Packaged license: `app/src/main/assets/licenses/cimbar-MIT.txt`

4. `robot36`
- Upstream: https://github.com/xdsopl/robot36
- License: 0BSD
- Local reference license: `.tmp_robot36_src/robot36-2/LICENSE`
- Packaged license: `app/src/main/assets/licenses/robot36-0BSD.txt`

## Network / Speed Test References

5. `LibreSpeed/speedtest`
- Upstream: https://github.com/librespeed/speedtest
- License: LGPL-3.0-or-later
- Usage in this app: Libre Speed Test experiment page and related local speed-test integration
- Packaged license: `app/src/main/assets/licenses/librespeed-LGPL-3.0.txt`

6. `vastsa/FileCodeBox`
- Upstream: https://github.com/vastsa/FileCodeBox
- License: LGPL-3.0
- Usage in this app: FileCodeBox-based file cabinet / temporary file server experiment
- Packaged license: `app/src/main/assets/licenses/filecodebox-LGPL-3.0.txt`

7. `KnightWhoSayNi/android-iperf`
- Upstream: https://github.com/KnightWhoSayNi/android-iperf
- License: MIT
- Usage in this app: Android-side iperf integration reference for the iperf experiment page
- Packaged license: `app/src/main/assets/licenses/android-iperf-MIT.txt`

8. `iperf2`
- Upstream: https://sourceforge.net/projects/iperf2/
- Version in this app: `2.2.1`
- License: BSD-style
- Packaged license: `app/src/main/assets/licenses/iperf2-BSD.txt`

9. `iperf3`
- Upstream: https://github.com/esnet/iperf
- Version in this app: `3.20`
- License: BSD-3-Clause
- Packaged license: `app/src/main/assets/licenses/iperf3-BSD-3-Clause.txt`

## Other Referenced Projects

10. `li1055107552/p2p`
- Upstream: https://github.com/li1055107552/p2p
- Usage in this app: NAT3-NAT4 UDP punching workflow reference
- Note: no upstream license text was found at integration time; this project avoids direct source copy and keeps only logic-level reference attribution

11. `HMBSbige/NatTypeTester`
- Upstream: https://github.com/HMBSbige/NatTypeTester
- License: MIT
- Usage in this app: NAT behavior detection flow reference

12. `nxtrace/NextTraceroute`
- Upstream: https://github.com/nxtrace/NextTraceroute
- License: GPL-3.0-or-later
- Usage in this app: TraceRoute module workflow reference / porting inspiration

13. Additional libraries
- `okhttp` (Apache-2.0): https://github.com/square/okhttp
- `dnsjava` (BSD-3-Clause): https://github.com/dnsjava/dnsjava

## Compliance Notes

- MIT / BSD / 0BSD: keep copyright notices and license texts when distributing.
- MPL-2.0: if you modify MPL-covered files and distribute them, provide the modified source files under MPL terms.
- LGPL-3.0 / LGPL-3.0-or-later: keep license texts and provide the corresponding compliance information required for redistribution.
- GPL-related references in this repository are documented for attribution and transparency; distribution obligations depend on the exact integration method and redistributed code.
