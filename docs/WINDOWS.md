# Running on Windows

Three separate questions, with three different answers. Take them in order.

| Component | On Windows | How |
|---|---|---|
| **Agent** (on each monitored server) | Yes, natively | `.exe` / `.msi` installer, runs as a Windows service |
| **Proxy** (collects at a site) | Yes, natively | same installer |
| **Server** + database + web interface | Through Docker | WSL2, below |

## The server

There is no native Windows build of the server, and making one is a bigger job
than it looks: it would need PostgreSQL installed and managed separately, the
web interface served by something, and TimescaleDB — which Timescale no longer
ships for Windows, so history would be uncompressed. Running the Linux
containers under WSL2 avoids all of that and is the same stack that is tested.

### Which runtime

Windows cannot run Linux containers directly. Both options below are a Linux VM
with Docker inside it; the difference is what wraps it.

**WSL2 with Docker Engine inside it — recommended.** Free, Apache-2.0, no
licence to review, and identical to the Linux install because it *is* the Linux
install.

```powershell
# PowerShell as Administrator
wsl --install -d Ubuntu
# reboot if prompted, then set a UNIX username and password
```

Then inside the Ubuntu shell, exactly the Linux runbook:

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker "$USER"    # log out of the WSL shell and back in

git clone <your-repo> ~/nms && cd ~/nms
./deploy/init-env.sh
docker compose up -d
```

**Docker Desktop** is the alternative. It is easier to set up and it bundles
everything — but check the licence before installing it at work: Docker
requires a paid subscription for commercial use in organisations above 250
employees or $10M annual revenue. That is a procurement conversation, not a
technical one, so it is worth knowing before you start rather than after.

### Make it start at boot

A monitoring system that stops when the server reboots is worse than none, and
WSL2 does not start on its own. Register it as a scheduled task:

**Run the task as the account that installed the distribution, not as SYSTEM.**
WSL registers a distribution per Windows user profile, so `wsl -d Ubuntu` under
SYSTEM looks in a profile where no distribution exists and fails with *there is
no distribution with the supplied name* — at boot, where nobody sees it. The
monitoring system is then simply not running, which is the one failure it must
not have.

```powershell
# PowerShell as Administrator. Prompts for that account's password, which
# Windows stores so the task can run with nobody signed in.
$action  = New-ScheduledTaskAction -Execute 'wsl.exe' `
           -Argument '-d Ubuntu -u root -- /bin/sh -c "service docker start && cd /home/<you>/nms && docker compose up -d"'
$trigger = New-ScheduledTaskTrigger -AtStartup
Register-ScheduledTask -TaskName 'NMS' -Action $action -Trigger $trigger `
    -User "$env:USERDOMAIN\$env:USERNAME" -RunLevel Highest `
    -Password (Read-Host 'Windows password' -AsSecureString |
               ForEach-Object { [Runtime.InteropServices.Marshal]::PtrToStringAuto(
                   [Runtime.InteropServices.Marshal]::SecureStringToBSTR($_)) })
```

If storing that password is not acceptable, the alternative is to enable systemd
in WSL (`[boot] systemd=true` in `/etc/wsl.conf`) and let Docker's own unit start
the stack with `restart: unless-stopped`, but something must still start WSL
itself at boot — it does not start on its own.

Then **test it by rebooting**, and check `docker compose ps` afterwards rather
than assuming. An untested boot path is an assumption, and this one has a known
way to fail silently.

### Reaching it from other machines

This is the part that costs people an afternoon. Ports published inside WSL2
are reachable from the Windows host itself, but not necessarily from the rest of
the network.

On Windows 11 22H2 and later, turn on mirrored networking, which makes WSL2
share the host's network directly and removes the problem:

```ini
# %USERPROFILE%\.wslconfig
[wsl2]
networkingMode=mirrored
```

`wsl --shutdown` afterwards for it to take effect.

On older Windows, forward the ports explicitly instead:

```powershell
$wslIp = (wsl hostname -I).Trim().Split(' ')[0]
netsh interface portproxy add v4tov4 listenport=3000 listenaddress=0.0.0.0 `
      connectport=3000 connectaddress=$wslIp
New-NetFirewallRule -DisplayName 'NMS web' -Direction Inbound `
      -LocalPort 3000 -Protocol TCP -Action Allow
```

The WSL2 address changes on reboot, so this needs re-running — which is the
reason to prefer mirrored networking where it is available.

### Monitoring devices on the LAN

The machine has to be on the network it is monitoring. Obvious written down,
and easy to lose an afternoon to: a laptop on a phone's hotspot has a default
gateway of `192.0.0.1` and no route to any `192.168.x.x` LAN at all, so every
camera reads OFFLINE while the camera is fine and the tool is working exactly
as designed. Check with `ping` from the host before blaming anything else. A
machine that stays plugged into the camera network is the right home for this;
a laptop that moves between networks is not.

Outbound otherwise works normally: ICMP, SNMP and RTSP from inside WSL2 reach
cameras and switches on the office network through the host. What does not work is
`network_mode: host` for a proxy — containers sit behind the VM, so a proxy
needs the native installer below if it must see the LAN as the host sees it.

## The agent and proxy as an .exe

These are packaged with `jpackage`, which bundles a trimmed JRE with the
application. The target machines need no Java installed and no Java kept
patched, which on an estate of servers is the difference between an install
people accept and one they argue about.

```powershell
# On a Windows machine with JDK 21 and the WiX Toolset:
#   winget install EclipseAdoptium.Temurin.21.JDK
#   winget install WiXToolset.WiXToolset

.\deploy\windows\build-installer.ps1                        # agent, .exe
.\deploy\windows\build-installer.ps1 -Type msi              # agent, .msi for Group Policy
.\deploy\windows\build-installer.ps1 -Component proxy        # proxy
```

**The build must run on Windows.** `jpackage` cannot cross-compile — run it on
Linux and you get a Linux package. This is the one step that cannot be done
from a Mac.

The installer registers a Windows service that starts at boot without anyone
signed in:

```powershell
Get-Service nms-agent
```

Configure the agent at `C:\ProgramData\NMS\agent.conf`:

```properties
agent.hostname=win-app-01
agent.allowed.servers=10.20.0.5
```

The name must match the host created in the interface. Then link
**Template: Linux by agent** — the name is wrong for this case, but its items go
through OSHI, which reads native APIs on each platform, so CPU, memory, swap,
filesystems, uptime and processes all return real figures on Windows.

Two items in it are Linux concepts and will not:

- **Load average** — Windows has no equivalent. Reported as unsupported, with
  the reason, rather than as a misleading zero. Disable the three load items and
  the `{$LOAD.AVG.PER.CPU.MAX}` trigger on Windows hosts.
- **CPU iowait and steal** — these read **0%**, not unsupported, because the
  counters exist in the API and are simply never populated on Windows. Do not
  read a flat zero there as "storage is healthy"; it means "not measured".

Where the server cannot reach the machine — a laptop, or a host behind NAT —
invert the direction:

```properties
agent.active.enabled=true
agent.server.url=https://monitoring.example.com
```

## ICMP on Windows

`PING.EXE` takes different arguments and prints a different summary from the
Linux one, including listing its timings as minimum/maximum/**average** where
iputils prints min/**avg**/max. Both dialects are handled, and the parser
detects which it is reading from the output rather than from the host OS, so a
proxy behaves the same either way.

Windows translates that output, so the figures are matched rather than the
labels — a German or French install reports real numbers instead of reading as
unreachable.

## What has and has not been verified

**Verified:** the agent and proxy both run correctly as `jpackage` application
images with a bundled runtime, including the proxy's Spring Boot jar, whose
nested-jar loader is the part most likely to object. Windows `ping` output
parsing is covered by tests, including the field-order trap and localised
output.

**Not verified:** no step of this was executed on Windows — there is no Windows
machine in the environment this was built in. The packaging mechanism is proven
on Linux and the arguments are platform-independent, but the `.exe` itself, the
service registration, WSL2 networking and the scheduled task have not been run.
Treat the first install as a test, on a machine you do not mind rebuilding.
