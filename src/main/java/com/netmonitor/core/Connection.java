package com.netmonitor.core;

import java.util.Objects;

/**
 * Представление одного сетевого соединения (строка таблицы соединений ОС).
 */
public final class Connection {

    private final String protocol;
    private final String localAddress;
    private final int localPort;
    private final String remoteAddress;
    private final int remotePort;
    private final String state;
    private final String pid;
    private final String process;

    public Connection(String protocol, String localAddress, int localPort,
                      String remoteAddress, int remotePort, String state,
                      String pid, String process) {
        this.protocol = protocol;
        this.localAddress = localAddress;
        this.localPort = localPort;
        this.remoteAddress = remoteAddress;
        this.remotePort = remotePort;
        this.state = state;
        this.pid = pid;
        this.process = process;
    }

    public String getProtocol() {
        return protocol;
    }

    public String getLocalAddress() {
        return localAddress;
    }

    public int getLocalPort() {
        return localPort;
    }

    public String getRemoteAddress() {
        return remoteAddress;
    }

    public int getRemotePort() {
        return remotePort;
    }

    public String getState() {
        return state;
    }

    public String getPid() {
        return pid;
    }

    public String getProcess() {
        return process;
    }

    public String localEndpoint() {
        return localAddress + ":" + (localPort > 0 ? localPort : "*");
    }

    public String remoteEndpoint() {
        return remoteAddress + ":" + (remotePort > 0 ? remotePort : "*");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Connection that)) {
            return false;
        }
        return localPort == that.localPort
                && remotePort == that.remotePort
                && Objects.equals(protocol, that.protocol)
                && Objects.equals(localAddress, that.localAddress)
                && Objects.equals(remoteAddress, that.remoteAddress)
                && Objects.equals(state, that.state)
                && Objects.equals(pid, that.pid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(protocol, localAddress, localPort, remoteAddress, remotePort, state, pid);
    }
}
