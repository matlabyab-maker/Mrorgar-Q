package com.mrorgar.q;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Arrays;

/** DNS-only local VPN. It forwards IPv4 UDP DNS packets to the user-selected DNS servers;
 * ordinary web traffic is not routed through this service. */
public final class DnsVpnService extends VpnService {
    public static final String ACTION_START = "com.mrorgar.q.DNS_START";
    public static final String ACTION_STOP = "com.mrorgar.q.DNS_STOP";
    public static final String EXTRA_PRIMARY = "dns_primary";
    public static final String EXTRA_SECONDARY = "dns_secondary";
    private static final String CHANNEL_ID = "mrorgar_dns_vpn";
    private static final int NOTIFICATION_ID = 7812;

    private volatile boolean running;
    private ParcelFileDescriptor tun;
    private Thread worker;
    private String primary;
    private String secondary;

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopDnsVpn();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_START.equals(intent.getAction())) {
            primary = intent.getStringExtra(EXTRA_PRIMARY);
            secondary = intent.getStringExtra(EXTRA_SECONDARY);
        }
        try {
            if ((primary == null || primary.trim().isEmpty()) && (secondary == null || secondary.trim().isEmpty())) {
                stopSelf();
                return START_NOT_STICKY;
            }
            startForeground(NOTIFICATION_ID, makeNotification());
            establishVpn();
        } catch (Exception e) {
            stopDnsVpn();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private Notification makeNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Mrorgar DNS", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("DNS server connection status");
            nm.createNotificationChannel(channel);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Mrorgar-Q DNS فعال است")
                .setContentText("فقط درخواست‌های DNS از سرور انتخابی عبور می‌کنند")
                .setOngoing(true).build();
    }

    private synchronized void establishVpn() throws Exception {
        closeTunnelOnly();
        Builder builder = new Builder().setSession("Mrorgar-Q DNS")
                .setMtu(1500).addAddress("10.77.0.1", 32);
        String first = validIpv4OrNull(primary);
        String second = validIpv4OrNull(secondary);
        if (first == null) first = second;
        if (first == null) throw new IllegalArgumentException("No valid DNS server");
        builder.addDnsServer(first).addRoute(first, 32);
        if (second != null && !second.equals(first)) {
            builder.addDnsServer(second).addRoute(second, 32);
        }
        tun = builder.establish();
        if (tun == null) throw new IllegalStateException("VPN permission was not granted");
        running = true;
        final ParcelFileDescriptor localTun = tun;
        worker = new Thread(() -> packetLoop(localTun), "Mrorgar-DNS-forwarder");
        worker.setDaemon(true);
        worker.start();
    }

    private String validIpv4OrNull(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        try {
            InetAddress a = InetAddress.getByName(value.trim());
            return a instanceof Inet4Address && value.trim().matches("[0-9.]+") ? a.getHostAddress() : null;
        } catch (Exception e) { return null; }
    }

    private void packetLoop(ParcelFileDescriptor localTun) {
        byte[] packet = new byte[32767];
        try (FileInputStream input = new FileInputStream(localTun.getFileDescriptor());
             FileOutputStream output = new FileOutputStream(localTun.getFileDescriptor())) {
            while (running && tun == localTun) {
                int count = input.read(packet);
                if (count <= 0) continue;
                byte[] response = forwardDnsPacket(packet, count);
                if (response != null && running) {
                    output.write(response);
                    output.flush();
                }
            }
        } catch (Exception ignored) {
            // Closing the TUN descriptor when stopping intentionally exits this loop.
        }
    }

    private byte[] forwardDnsPacket(byte[] packet, int count) {
        if (count < 28 || ((packet[0] >> 4) & 0x0f) != 4 || (packet[9] & 0xff) != 17) return null;
        int ipHeader = (packet[0] & 0x0f) * 4;
        if (ipHeader < 20 || count < ipHeader + 8) return null;
        int totalLength = u16(packet, 2);
        if (totalLength < ipHeader + 8 || totalLength > count) return null;
        int sourcePort = u16(packet, ipHeader);
        int destPort = u16(packet, ipHeader + 2);
        int udpLength = u16(packet, ipHeader + 4);
        if (destPort != 53 || udpLength < 8 || ipHeader + udpLength > totalLength) return null;
        byte[] sourceIp = Arrays.copyOfRange(packet, 12, 16);
        byte[] destIp = Arrays.copyOfRange(packet, 16, 20);
        byte[] query = Arrays.copyOfRange(packet, ipHeader + 8, ipHeader + udpLength);
        try (DatagramSocket socket = new DatagramSocket()) {
            if (!protect(socket)) return null;
            socket.setSoTimeout(3500);
            InetAddress dns = InetAddress.getByAddress(destIp);
            socket.send(new DatagramPacket(query, query.length, dns, 53));
            byte[] answerBuffer = new byte[8192];
            DatagramPacket answer = new DatagramPacket(answerBuffer, answerBuffer.length);
            socket.receive(answer);
            byte[] dnsBytes = Arrays.copyOf(answer.getData(), answer.getLength());
            return makeIpv4UdpResponse(sourceIp, destIp, sourcePort, destPort, packet, dnsBytes);
        } catch (Exception ignored) { return null; }
    }

    private byte[] makeIpv4UdpResponse(byte[] clientIp, byte[] dnsIp, int clientPort, int dnsPort,
                                       byte[] original, byte[] dnsPayload) {
        int udpLength = 8 + dnsPayload.length;
        int totalLength = 20 + udpLength;
        if (totalLength > 32767) return null;
        byte[] out = new byte[totalLength];
        out[0] = 0x45;
        out[1] = original[1];
        put16(out, 2, totalLength);
        out[4] = original[4]; out[5] = original[5];
        out[6] = 0x40; out[7] = 0;
        out[8] = 64; out[9] = 17;
        System.arraycopy(dnsIp, 0, out, 12, 4);
        System.arraycopy(clientIp, 0, out, 16, 4);
        put16(out, 10, checksum(out, 0, 20));
        put16(out, 20, dnsPort);
        put16(out, 22, clientPort);
        put16(out, 24, udpLength);
        put16(out, 26, 0); // IPv4 UDP checksum may be zero.
        System.arraycopy(dnsPayload, 0, out, 28, dnsPayload.length);
        return out;
    }

    private static int u16(byte[] b, int p) { return ((b[p] & 0xff) << 8) | (b[p + 1] & 0xff); }
    private static void put16(byte[] b, int p, int value) { b[p] = (byte) (value >>> 8); b[p + 1] = (byte) value; }
    private static int checksum(byte[] b, int offset, int length) {
        long sum = 0;
        for (int i = offset; i < offset + length; i += 2) {
            int word = (b[i] & 0xff) << 8;
            if (i + 1 < offset + length) word |= b[i + 1] & 0xff;
            sum += word;
        }
        while ((sum >>> 16) != 0) sum = (sum & 0xffff) + (sum >>> 16);
        return (int) (~sum) & 0xffff;
    }

    private synchronized void closeTunnelOnly() {
        running = false;
        ParcelFileDescriptor old = tun;
        tun = null;
        if (old != null) try { old.close(); } catch (Exception ignored) { }
        if (worker != null) { worker.interrupt(); worker = null; }
    }

    private synchronized void stopDnsVpn() {
        closeTunnelOnly();
        stopForeground(true);
    }

    @Override public void onDestroy() {
        stopDnsVpn();
        super.onDestroy();
    }
}
