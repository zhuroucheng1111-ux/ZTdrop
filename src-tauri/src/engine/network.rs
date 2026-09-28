use anyhow::{anyhow, Result};
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::path::Path;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};
use tokio::net::{TcpSocket, TcpStream, UdpSocket};
use tokio::sync::Mutex;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LanInterface {
    pub ip: Ipv4Addr,
    pub prefix: u8,
    pub index: u32,
}

impl LanInterface {
    pub fn contains(&self, peer: Ipv4Addr) -> bool {
        let mask = u32::MAX
            .checked_shl(32 - u32::from(self.prefix))
            .unwrap_or(0);
        (u32::from(self.ip) & mask) == (u32::from(peer) & mask)
    }

    fn broadcast(&self) -> Ipv4Addr {
        let mask = u32::MAX
            .checked_shl(32 - u32::from(self.prefix))
            .unwrap_or(0);
        Ipv4Addr::from(u32::from(self.ip) | !mask)
    }
}

pub struct NetworkPolicy {
    path: std::path::PathBuf,
    physical_lan_only: AtomicBool,
    send_lock: Mutex<()>,
    interfaces_cache: std::sync::Mutex<Option<(Instant, Vec<LanInterface>)>>,
}

impl NetworkPolicy {
    pub fn load(directory: &Path) -> Result<Self> {
        std::fs::create_dir_all(directory)?;
        // Keep the existing preference file so upgrades preserve the user's choice.
        let path = directory.join("exclude-virtual-adapters");
        let enabled = match std::fs::read_to_string(&path) {
            Ok(value) => value.trim() != "false",
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => true,
            Err(error) => return Err(error.into()),
        };
        Ok(Self {
            path,
            physical_lan_only: AtomicBool::new(enabled),
            send_lock: Mutex::new(()),
            interfaces_cache: std::sync::Mutex::new(None),
        })
    }

    pub fn physical_lan_only(&self) -> bool {
        self.physical_lan_only.load(Ordering::Relaxed)
    }

    pub fn set_physical_lan_only(&self, enabled: bool) -> Result<()> {
        std::fs::write(&self.path, if enabled { "true" } else { "false" })?;
        self.physical_lan_only.store(enabled, Ordering::Relaxed);
        *self.interfaces_cache.lock().unwrap() = None;
        Ok(())
    }

    pub fn interfaces(&self) -> Vec<LanInterface> {
        if self.physical_lan_only() {
            let mut cache = self.interfaces_cache.lock().unwrap();
            if let Some((when, nics)) = cache.as_ref() {
                if when.elapsed() < Duration::from_secs(2) {
                    return nics.clone();
                }
            }
            let nics = physical_interfaces();
            *cache = Some((Instant::now(), nics.clone()));
            nics
        } else {
            local_ip_address::list_afinet_netifas()
                .unwrap_or_default()
                .into_iter()
                .filter_map(|(_, ip)| match ip {
                    IpAddr::V4(ip) if ip.is_private() => Some(LanInterface {
                        ip,
                        prefix: 24,
                        index: 0,
                    }),
                    _ => None,
                })
                .collect()
        }
    }

    pub fn ipv4_addresses(&self) -> Vec<Ipv4Addr> {
        self.interfaces().into_iter().map(|nic| nic.ip).collect()
    }

    fn interface_for(&self, remote: IpAddr) -> Option<LanInterface> {
        let IpAddr::V4(remote) = remote else {
            return None;
        };
        self.interfaces()
            .into_iter()
            .find(|nic| nic.contains(remote))
    }

    pub fn allows_route_to(&self, remote: IpAddr) -> bool {
        !self.physical_lan_only() || self.interface_for(remote).is_some()
    }

    pub fn allows_connection(&self, local: SocketAddr, remote: SocketAddr) -> bool {
        if !self.physical_lan_only() {
            return true;
        }
        match (local.ip(), remote.ip()) {
            (IpAddr::V4(local), IpAddr::V4(remote)) => self
                .interfaces()
                .iter()
                .any(|nic| nic.ip == local && nic.contains(remote)),
            _ => false,
        }
    }

    pub async fn send_to(
        &self,
        socket: &UdpSocket,
        packet: &[u8],
        target: SocketAddr,
    ) -> Result<usize> {
        if !self.physical_lan_only() {
            let _guard = self.send_lock.lock().await;
            set_outgoing_interface(socket, 0)?;
            return Ok(socket.send_to(packet, target).await?);
        }
        let nic = self
            .interface_for(target.ip())
            .ok_or_else(|| anyhow!("对端不在物理局域网内"))?;
        let _guard = self.send_lock.lock().await;
        set_outgoing_interface(socket, nic.index)?;
        Ok(socket.send_to(packet, target).await?)
    }

    pub async fn send_broadcast(&self, socket: &UdpSocket, packet: &[u8], port: u16) {
        if !self.physical_lan_only() {
            let _guard = self.send_lock.lock().await;
            let _ = set_outgoing_interface(socket, 0);
            let _ = socket
                .send_to(packet, SocketAddr::from(([255, 255, 255, 255], port)))
                .await;
            return;
        }
        let _guard = self.send_lock.lock().await;
        for nic in self.interfaces() {
            if nic.prefix >= 31 {
                continue;
            }
            if set_outgoing_interface(socket, nic.index).is_ok() {
                let _ = socket
                    .send_to(packet, SocketAddr::new(IpAddr::V4(nic.broadcast()), port))
                    .await;
            }
        }
    }

    pub async fn connect_tcp(&self, target: SocketAddr) -> Result<TcpStream> {
        if !self.physical_lan_only() {
            return Ok(TcpStream::connect(target).await?);
        }
        let nic = self
            .interface_for(target.ip())
            .ok_or_else(|| anyhow!("对端不在物理局域网内"))?;
        let socket = TcpSocket::new_v4()?;
        socket.bind(SocketAddr::new(IpAddr::V4(nic.ip), 0))?;
        set_outgoing_interface(&socket, nic.index)?;
        Ok(socket.connect(target).await?)
    }
}

#[cfg(windows)]
fn set_outgoing_interface<S: std::os::windows::io::AsRawSocket>(
    socket: &S,
    index: u32,
) -> Result<()> {
    use windows_sys::Win32::Networking::WinSock::{
        setsockopt, IPPROTO_IP, IP_UNICAST_IF, SOCKET_ERROR,
    };
    let index = index.to_be();
    let result = unsafe {
        setsockopt(
            socket.as_raw_socket() as usize,
            IPPROTO_IP,
            IP_UNICAST_IF,
            (&index as *const u32).cast(),
            std::mem::size_of::<u32>() as i32,
        )
    };
    if result == SOCKET_ERROR {
        return Err(std::io::Error::last_os_error().into());
    }
    Ok(())
}

#[cfg(not(windows))]
fn set_outgoing_interface<S>(_socket: &S, _index: u32) -> Result<()> {
    Ok(())
}

#[cfg(windows)]
fn physical_interfaces() -> Vec<LanInterface> {
    use windows_sys::Win32::NetworkManagement::IpHelper::{
        GetAdaptersAddresses, GetIfEntry2, IF_TYPE_ETHERNET_CSMACD, IF_TYPE_IEEE80211,
        IP_ADAPTER_ADDRESSES_LH, MIB_IF_ROW2,
    };
    use windows_sys::Win32::Networking::WinSock::{AF_INET, SOCKADDR_IN};
    let mut size = 16 * 1024u32;
    for _ in 0..3 {
        let mut buffer = vec![0u64; (size as usize).div_ceil(8)];
        let first = buffer.as_mut_ptr().cast::<IP_ADAPTER_ADDRESSES_LH>();
        let status =
            unsafe { GetAdaptersAddresses(AF_INET as u32, 0, std::ptr::null(), first, &mut size) };
        if status == 111 {
            continue;
        } // ERROR_BUFFER_OVERFLOW
        if status != 0 {
            return Vec::new();
        }
        let mut result = Vec::new();
        let mut adapter = first;
        while !adapter.is_null() {
            let item = unsafe { &*adapter };
            let index = unsafe { item.Anonymous1.Anonymous.IfIndex };
            let mut row = MIB_IF_ROW2::default();
            row.InterfaceIndex = index;
            let status = unsafe { GetIfEntry2(&mut row) };
            // HardwareInterface and ConnectorPresent are OS properties, not name heuristics.
            let flags = row.InterfaceAndOperStatusFlags._bitfield;
            if status == 0
                && flags & 0b101 == 0b101
                && (row.Type == IF_TYPE_ETHERNET_CSMACD || row.Type == IF_TYPE_IEEE80211)
                && row.OperStatus == 1
            {
                let mut unicast = item.FirstUnicastAddress;
                while !unicast.is_null() {
                    let address = unsafe { &*unicast };
                    let sockaddr = address.Address.lpSockaddr;
                    if !sockaddr.is_null() && unsafe { (*sockaddr).sa_family } == AF_INET {
                        let v4 = unsafe { &*(sockaddr as *const SOCKADDR_IN) };
                        let ip = Ipv4Addr::from(unsafe { v4.sin_addr.S_un.S_addr }.to_ne_bytes());
                        if ip.is_private()
                            && !ip.is_link_local()
                            && (1..=30).contains(&address.OnLinkPrefixLength)
                        {
                            result.push(LanInterface {
                                ip,
                                prefix: address.OnLinkPrefixLength,
                                index,
                            });
                        }
                    }
                    unicast = address.Next;
                }
            }
            adapter = item.Next;
        }
        return result;
    }
    Vec::new()
}

#[cfg(not(windows))]
fn physical_interfaces() -> Vec<LanInterface> {
    Vec::new()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn subnet_membership_and_broadcast() {
        let nic = LanInterface {
            ip: Ipv4Addr::new(192, 168, 110, 223),
            prefix: 24,
            index: 16,
        };
        assert!(nic.contains(Ipv4Addr::new(192, 168, 110, 3)));
        assert!(!nic.contains(Ipv4Addr::new(192, 168, 111, 3)));
        assert_eq!(nic.broadcast(), Ipv4Addr::new(192, 168, 110, 255));
    }

    #[test]
    fn setting_preserves_previous_choice() -> Result<()> {
        let directory =
            std::env::temp_dir().join(format!("ztdrop-network-{:032x}", rand::random::<u128>()));
        let policy = NetworkPolicy::load(&directory)?;
        assert!(policy.physical_lan_only());
        policy.set_physical_lan_only(false)?;
        assert!(!NetworkPolicy::load(&directory)?.physical_lan_only());
        std::fs::remove_dir_all(directory)?;
        Ok(())
    }

    #[cfg(windows)]
    #[test]
    fn windows_reports_physical_lan_interface() {
        let nics = physical_interfaces();
        eprintln!("physical LAN interfaces: {nics:?}");
        assert!(nics.iter().all(|nic| nic.index != 0 && nic.ip.is_private()));
        if let Some(nic) = nics.first() {
            let socket = std::net::UdpSocket::bind("0.0.0.0:0").unwrap();
            set_outgoing_interface(&socket, nic.index).unwrap();
        }
    }
}
