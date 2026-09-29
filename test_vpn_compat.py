#!/usr/bin/env python3
"""VPN iface detection + IPv4-forcing for yt-dlp while Surfshark/WG is online."""
from __future__ import annotations

import os
import sys

ROOT = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, ROOT)

import nls_video_pipe as nvp

SURFSHARK_WG = (
    "11: surfshark_wg: <POINTOPOINT,NOARP,UP,LOWER_UP> mtu 1280 qdisc noqueue "
    "state UNKNOWN mode DEFAULT group default qlen 1000\\    link/none"
)
SURFSHARK_V6 = (
    "10: surfshark_ipv6: <BROADCAST,NOARP,UP,LOWER_UP> mtu 1500 qdisc noqueue "
    "state UNKNOWN mode DEFAULT group default qlen 1000\\    link/ether 6e:17:78:37:cc:31"
)
ETH = (
    "2: eth0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc fq_codel "
    "state UP mode DEFAULT group default qlen 1000\\    link/ether c4:c6:e6:cc:06:2a"
)
LO = "1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536 qdisc noqueue state UNKNOWN mode DEFAULT group default qlen 1000"
WIFI_DOWN = (
    "3: wlp3s0: <NO-CARRIER,BROADCAST,MULTICAST,UP> mtu 1500 qdisc noqueue "
    "state DOWN mode DORMANT group default qlen 1000"
)
TUN0 = (
    "8: tun0: <POINTOPOINT,MULTICAST,NOARP,UP,LOWER_UP> mtu 1500 qdisc fq_codel "
    "state UNKNOWN mode DEFAULT group default qlen 1000"
)
WG0 = "5: wg0: <POINTOPOINT,NOARP,UP,LOWER_UP> mtu 1420 qdisc noqueue state UNKNOWN mode DEFAULT group default qlen 1000"
DOCKER = "4: docker0: <NO-CARRIER,BROADCAST,MULTICAST,UP> mtu 1500 qdisc noqueue state DOWN"


def check(name: str, ok: bool, detail: str = "") -> None:
    status = "PASS" if ok else "FAIL"
    print(f"[{status}] {name}" + (f" — {detail}" if detail else ""))
    if not ok:
        raise SystemExit(1)


def main() -> None:
    check("surfshark_wg detected", nvp.iface_line_is_vpn(SURFSHARK_WG))
    check("surfshark_ipv6 detected", nvp.iface_line_is_vpn(SURFSHARK_V6))
    check("tun0 detected", nvp.iface_line_is_vpn(TUN0))
    check("wg0 detected", nvp.iface_line_is_vpn(WG0))
    check("ethernet ignored", not nvp.iface_line_is_vpn(ETH))
    check("loopback ignored", not nvp.iface_line_is_vpn(LO))
    check("wifi-down ignored", not nvp.iface_line_is_vpn(WIFI_DOWN))
    check("wlp3s0 not matched as wg", not nvp.iface_line_is_vpn(
        "3: wlp3s0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 state UP"
    ))
    check("docker ignored", not nvp.iface_line_is_vpn(DOCKER))

    blob = "\n".join([LO, ETH, WIFI_DOWN, SURFSHARK_V6, SURFSHARK_WG])
    check("parse blob finds VPN", nvp.vpn_interfaces_from_ip_output(blob) == ["surfshark_ipv6", "surfshark_wg"])

    os.environ.pop("GRAVITY_FORCE_IPV4", None)
    os.environ.pop("GRAVITY_FORCE_IPV6", None)
    os.environ.pop("GRAVITY_USE_VPN", None)
    os.environ.pop("GRAVITY_VPN_REQUIRED", None)
    check("HKG default: no auto ipv4", not nvp.force_ipv4_for_downloads(vpn_up=True))
    check("no ipv4 when vpn_up false", not nvp.force_ipv4_for_downloads(vpn_up=False))
    os.environ["GRAVITY_USE_VPN"] = "1"
    check("GRAVITY_USE_VPN=1 enables ipv4", nvp.force_ipv4_for_downloads())
    os.environ.pop("GRAVITY_USE_VPN", None)
    os.environ["GRAVITY_FORCE_IPV4"] = "1"
    check("GRAVITY_FORCE_IPV4=1", nvp.force_ipv4_for_downloads(vpn_up=False))
    os.environ["GRAVITY_FORCE_IPV4"] = "0"
    check("GRAVITY_FORCE_IPV4=0 overrides VPN", not nvp.force_ipv4_for_downloads(vpn_up=True))
    os.environ.pop("GRAVITY_FORCE_IPV4", None)
    os.environ["GRAVITY_FORCE_IPV6"] = "1"
    check("GRAVITY_FORCE_IPV6=1 disables", not nvp.force_ipv4_for_downloads(vpn_up=True))
    os.environ.pop("GRAVITY_FORCE_IPV6", None)

    args = nvp.vpn_compat_ytdlp_args(vpn_up=True, source_address=None)
    check("cli has --force-ipv4", "--force-ipv4" in args)
    check("cli has socket-timeout", "--socket-timeout" in args)
    bound = nvp.vpn_compat_ytdlp_args(vpn_up=True, source_address="10.64.0.8")
    check("cli binds tunnel IP", "--source-address" in bound and "10.64.0.8" in bound)
    check("cli still force-ipv4 when bound", "--force-ipv4" in bound)
    opts = nvp.vpn_compat_ytdlp_opts(vpn_up=True, source_address="10.64.0.8")
    check("api source_address tunnel", opts.get("source_address") == "10.64.0.8")
    check("api does not bind 0.0.0.0", opts.get("source_address") != "0.0.0.0")

    addr_blob = (
        "2: eth0    inet 192.0.2.10/24 brd 192.0.2.255 scope global\n"
        "11: surfshark_wg    inet 10.64.0.8/16 brd 10.64.255.255 scope global noprefixroute surfshark_wg\n"
    )
    check(
        "parse tunnel ipv4 from addr blob",
        nvp.vpn_tunnel_ipv4_from_addr_output(addr_blob, ["surfshark_wg"]) == "10.64.0.8",
    )
    check(
        "ignore loopback addr",
        nvp.vpn_addr_line_ipv4("1: lo    inet 127.0.0.1/8 scope host lo") is None,
    )

    astrill_addr = (
        "2: eth0    inet 192.0.2.10/24 brd 192.0.2.255 scope global\n"
        "8: tun0    inet 198.18.0.8/20 scope global tun0\n"
    )
    check("198.18 is Astrill tun", nvp.ip_looks_astrill("198.18.0.8"))
    check("10.64 is not Astrill", not nvp.ip_looks_astrill("10.64.0.8"))
    check("tun0 only → astrill", nvp.vpn_provider_from_ifaces(["tun0"]) == "astrill")
    check("surfshark_wg only → surfshark", nvp.vpn_provider_from_ifaces(["surfshark_wg"]) == "surfshark")
    check("both → stacked", nvp.vpn_provider_from_ifaces(["surfshark_wg", "tun0"]) == "stacked")
    check("empty → none", nvp.vpn_provider_from_ifaces([]) == "none")
    check(
        "astrill tun0 ipv4",
        nvp.vpn_tunnel_ipv4_for_provider("astrill", astrill_addr, ["tun0"]) == "198.18.0.8",
    )
    mixed = addr_blob + "8: tun0    inet 198.18.0.8/20 scope global tun0\n"
    check(
        "prefer Astrill tun when asked",
        nvp.vpn_tunnel_ipv4_for_provider("astrill", mixed, ["surfshark_wg", "tun0"]) == "198.18.0.8",
    )
    check(
        "prefer Surfshark when asked",
        nvp.vpn_tunnel_ipv4_for_provider("surfshark", mixed, ["surfshark_wg", "tun0"]) == "10.64.0.8",
    )

    print("all vpn-compat tests passed")


if __name__ == "__main__":
    main()
