package vproxyx.websocks;

import vclient.HttpClient;
import vclient.HttpResponse;
import vproxy.app.CertKeyHolder;
import vproxy.component.check.CheckProtocol;
import vproxy.component.check.HealthCheckConfig;
import vproxy.component.elgroup.EventLoopGroup;
import vproxy.component.ssl.CertKey;
import vproxy.component.svrgroup.Method;
import vproxy.component.svrgroup.ServerGroup;
import vproxy.connection.NetEventLoop;
import vproxy.dns.Resolver;
import vproxy.selector.SelectorEventLoop;
import vproxy.selector.wrap.h2streamed.H2StreamedClientFDs;
import vproxy.selector.wrap.kcp.KCPFDs;
import vproxy.util.BlockCallback;
import vproxy.util.Logger;
import vproxy.util.Utils;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ConfigProcessor {
    public final String fileName;
    public final EventLoopGroup hcLoopGroup;
    public final EventLoopGroup workerLoopGroup;
    private int socks5ListenPort = 0;
    private int httpConnectListenPort = 0;
    private int ssListenPort = 0;
    private String ssPassword = "";
    private int dnsListenPort = 0;
    private boolean gateway = false;
    private Map<String, ServerGroup> servers = new HashMap<>();
    private Map<String, List<DomainChecker>> domains = new HashMap<>();
    private Map<String, List<DomainChecker>> proxyResolves = new HashMap<>();
    private Map<String, List<DomainChecker>> noProxyDomains = new HashMap<>();
    private List<DomainChecker> httpsRelayDomains = new ArrayList<>();
    private List<DomainChecker> proxyHttpsRelayDomains = new ArrayList<>();
    private String autoSignCert;
    private String autoSignKey;
    private File autoSignWorkingDirectory;
    private List<List<String>> httpsRelayCertKeyFiles = new ArrayList<>();
    private List<CertKey> httpsRelayCertKeys = new ArrayList<>();
    private boolean directRelay = false;
    private Boolean proxyRelay = null; // null means auto
    private String user;
    private String pass;
    private String cacertsPath;
    private String cacertsPswd;
    private boolean verifyCert = true;
    private boolean strictMode = false;
    private int poolSize = 10;
    private boolean noHealthCheck = false;
    private boolean proxyHttpsRelayDomainMerge = false;

    private int pacServerPort;

    public ConfigProcessor(String fileName, EventLoopGroup hcLoopGroup, EventLoopGroup workerLoopGroup) {
        this.fileName = fileName;
        this.hcLoopGroup = hcLoopGroup;
        this.workerLoopGroup = workerLoopGroup;
    }

    public int getSocks5ListenPort() {
        return socks5ListenPort;
    }

    public int getHttpConnectListenPort() {
        return httpConnectListenPort;
    }

    public int getSsListenPort() {
        return ssListenPort;
    }

    public String getSsPassword() {
        return ssPassword;
    }

    public int getDnsListenPort() {
        return dnsListenPort;
    }

    public boolean isGateway() {
        return gateway;
    }

    public Map<String, ServerGroup> getServers() {
        return servers;
    }

    public LinkedHashMap<String, List<DomainChecker>> getDomains() {
        LinkedHashMap<String, List<DomainChecker>> ret = new LinkedHashMap<>();
        for (String key : domains.keySet()) {
            if (!key.equals("DEFAULT")) {
                ret.put(key, domains.get(key));
            }
        }
        // put DEFAULT to the last
        if (domains.containsKey("DEFAULT")) {
            ret.put("DEFAULT", domains.get("DEFAULT"));
        }
        return ret;
    }

    public LinkedHashMap<String, List<DomainChecker>> getProxyResolves() {
        LinkedHashMap<String, List<DomainChecker>> ret = new LinkedHashMap<>();
        for (String key : proxyResolves.keySet()) {
            if (!key.equals("DEFAULT")) {
                ret.put(key, proxyResolves.get(key));
            }
        }
        // put DEFAULT to the last
        if (proxyResolves.containsKey("DEFAULT")) {
            ret.put("DEFAULT", proxyResolves.get("DEFAULT"));
        }
        return ret;
    }

    public LinkedHashMap<String, List<DomainChecker>> getNoProxyDomains() {
        LinkedHashMap<String, List<DomainChecker>> ret = new LinkedHashMap<>();
        for (String key : noProxyDomains.keySet()) {
            if (!key.equals("DEFAULT")) {
                ret.put(key, noProxyDomains.get(key));
            }
        }
        // put DEFAULT to the last
        if (noProxyDomains.containsKey("DEFAULT")) {
            ret.put("DEFAULT", noProxyDomains.get("DEFAULT"));
        }
        return ret;
    }

    public boolean isDirectRelay() {
        return directRelay;
    }

    public boolean isProxyRelay() {
        return proxyRelay == null ? !httpsRelayDomains.isEmpty() : proxyRelay;
    }

    public List<DomainChecker> getHTTPSRelayDomains() {
        return httpsRelayDomains;
    }

    public List<DomainChecker> getProxyHTTPSRelayDomains() {
        return proxyHttpsRelayDomains;
    }

    public String getAutoSignCert() {
        return autoSignCert;
    }

    public String getAutoSignKey() {
        return autoSignKey;
    }

    public File getAutoSignWorkingDirectory() {
        return autoSignWorkingDirectory;
    }

    public List<CertKey> getHTTPSRelayCertKeys() {
        return httpsRelayCertKeys;
    }

    public String getUser() {
        return user;
    }

    public String getPass() {
        return pass;
    }

    public String getCacertsPath() {
        return cacertsPath;
    }

    public String getCacertsPswd() {
        return cacertsPswd;
    }

    public boolean isVerifyCert() {
        return verifyCert;
    }

    public boolean isStrictMode() {
        return strictMode;
    }

    public int getPoolSize() {
        return poolSize;
    }

    public int getPacServerPort() {
        return pacServerPort;
    }

    private ServerGroup getGroup(String alias) throws Exception {
        if (alias == null) {
            alias = "DEFAULT";
        }
        if (servers.containsKey(alias))
            return servers.get(alias);
        ServerGroup grp = new ServerGroup(alias, hcLoopGroup,
            new HealthCheckConfig(5_000, 30_000, 1, 2, noHealthCheck ? CheckProtocol.none : CheckProtocol.tcp)
            , Method.wrr);
        servers.put(alias, grp);
        return grp;
    }

    private List<DomainChecker> getDomainList(String alias) {
        if (alias == null) {
            alias = "DEFAULT";
        }
        if (domains.containsKey(alias))
            return domains.get(alias);
        List<DomainChecker> checkers = new LinkedList<>();
        domains.put(alias, checkers);
        return checkers;
    }

    private List<DomainChecker> getProxyResolveList(String alias) {
        if (alias == null) {
            alias = "DEFAULT";
        }
        if (proxyResolves.containsKey(alias))
            return proxyResolves.get(alias);
        List<DomainChecker> checkers = new LinkedList<>();
        proxyResolves.put(alias, checkers);
        return checkers;
    }

    private List<DomainChecker> getNoProxyDomainList(String alias) {
        if (alias == null) {
            alias = "DEFAULT";
        }
        if (noProxyDomains.containsKey(alias))
            return noProxyDomains.get(alias);
        List<DomainChecker> checkers = new LinkedList<>();
        noProxyDomains.put(alias, checkers);
        return checkers;
    }

    public void parse() throws Exception {
        FileInputStream inputStream = new FileInputStream(fileName);
        BufferedReader br = new BufferedReader(new InputStreamReader(inputStream));
        int step = 0;
        String currentAlias = null;
        // 0 -> normal
        // 1 -> proxy.server.list
        // 2 -> proxy.domain.list
        // 3 -> proxy.resolve.list
        // 4 -> no-proxy.domain.list
        // 5 -> https-relay.domain.list
        // 6 -> agent.https-relay.cert-key.list
        // 7 -> proxy.https-relay.domain.list
        String line;
        while ((line = br.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#"))
                continue; // ignore whitespace lines and comment lines

            if (step == 0) {
                if (line.startsWith("agent.listen ") || line.startsWith("agent.socks5.listen ")) {
                    int prefixLen = (line.startsWith("agent.listen ")) ? "agent.listen ".length() : "agent.socks5.listen ".length();
                    String port = line.substring(prefixLen).trim();
                    try {
                        socks5ListenPort = Integer.parseInt(port);
                    } catch (NumberFormatException e) {
                        throw new Exception("invalid agent.listen, expecting an integer");
                    }
                    if (socks5ListenPort < 1 || socks5ListenPort > 65535) {
                        throw new Exception("invalid agent.listen, port number out of range");
                    }
                } else if (line.startsWith("agent.httpconnect.listen ")) {
                    String port = line.substring("agent.httpconnect.listen ".length()).trim();
                    try {
                        httpConnectListenPort = Integer.parseInt(port);
                    } catch (NumberFormatException e) {
                        throw new Exception("invalid agent.httpconnect.listen, expecting an integer");
                    }
                    if (httpConnectListenPort < 1 || httpConnectListenPort > 65535) {
                        throw new Exception("invalid agent.httpconnect.listen, port number out of range");
                    }
                } else if (line.startsWith("agent.ss.listen ")) {
                    String port = line.substring("agent.ss.listen ".length()).trim();
                    try {
                        ssListenPort = Integer.parseInt(port);
                    } catch (NumberFormatException e) {
                        throw new Exception("invalid agent.ss.listen, expecting an integer");
                    }
                    if (ssListenPort < 1 || ssListenPort > 65535) {
                        throw new Exception("invalid agent.ss.listen, port number out of range");
                    }
                } else if (line.startsWith("agent.ss.password ")) {
                    ssPassword = line.substring("agent.ss.password ".length()).trim();
                } else if (line.startsWith("agent.dns.listen ")) {
                    String port = line.substring("agent.dns.listen ".length()).trim();
                    try {
                        dnsListenPort = Integer.parseInt(port);
                    } catch (NumberFormatException e) {
                        throw new Exception("invalid agent.dns.listen, expecting an integer");
                    }
                    if (dnsListenPort < 1 || dnsListenPort > 65535) {
                        throw new Exception("invalid agent.dns.listen, port number out of range");
                    }
                } else if (line.startsWith("agent.gateway ")) {
                    String val = line.substring("agent.gateway ".length()).trim();
                    switch (val) {
                        case "on":
                            gateway = true;
                            break;
                        case "off":
                            gateway = false;
                            break;
                        default:
                            throw new Exception("invalid value for agent.gateway: " + val);
                    }
                } else if (line.startsWith("agent.direct-relay ")) {
                    String val = line.substring("agent.direct-relay ".length()).trim();
                    switch (val) {
                        case "on":
                            directRelay = true;
                            break;
                        case "off":
                            directRelay = false;
                            break;
                        default:
                            throw new Exception("invalid value for agent.direct-relay: " + val);
                    }
                } else if (line.startsWith("agent.proxy-relay ")) {
                    String val = line.substring("agent.proxy-relay ".length()).trim();
                    switch (val) {
                        case "on":
                            proxyRelay = true;
                            break;
                        case "off":
                            proxyRelay = false;
                            break;
                        case "auto":
                            proxyRelay = null;
                        default:
                            throw new Exception("invalid value for agent.proxy-relay: " + val);
                    }
                } else if (line.startsWith("proxy.server.auth ")) {
                    String auth = line.substring("proxy.server.auth ".length()).trim();
                    String[] userpass = auth.split(":");
                    if (userpass.length != 2)
                        throw new Exception("invalid proxy.server.auth: " + auth);
                    user = userpass[0].trim();
                    if (user.isEmpty())
                        throw new Exception("invalid proxy.server.auth: user is empty");
                    pass = userpass[1].trim();
                    if (pass.isEmpty())
                        throw new Exception("invalid proxy.server.auth: pass is empty");
                } else if (line.startsWith("proxy.server.hc ")) {
                    String hc = line.substring("proxy.server.hc ".length());
                    if (hc.equals("on")) {
                        noHealthCheck = false;
                    } else if (hc.equals("off")) {
                        noHealthCheck = true;
                    } else {
                        throw new Exception("invalid value for proxy.server.hc: " + hc);
                    }
                } else if (line.startsWith("agent.cacerts.path ")) {
                    String path = line.substring("agent.cacerts.path ".length()).trim();
                    if (path.isEmpty())
                        throw new Exception("cacert path not specified");
                    cacertsPath = Utils.filename(path);
                } else if (line.startsWith("agent.cacerts.pswd ")) {
                    String pswd = line.substring("agent.cacerts.pswd ".length()).trim();
                    if (pswd.isEmpty())
                        throw new Exception("cacert path not specified");
                    cacertsPswd = pswd;
                } else if (line.startsWith("agent.cert.verify ")) {
                    String val = line.substring("agent.cert.verify ".length()).trim();
                    switch (val) {
                        case "on":
                            verifyCert = true;
                            break;
                        case "off":
                            verifyCert = false;
                            break;
                        default:
                            throw new Exception("invalid value for agent.cert.verify: " + val);
                    }
                } else if (line.startsWith("agent.strict ")) {
                    String val = line.substring("agent.strict ".length()).trim();
                    switch (val) {
                        case "on":
                            strictMode = true;
                            break;
                        case "off":
                            strictMode = false;
                            break;
                        default:
                            throw new Exception("invalid value for agent.strict: " + val);
                    }
                } else if (line.startsWith("agent.pool ")) {
                    String size = line.substring("agent.pool ".length()).trim();
                    int intSize;
                    try {
                        intSize = Integer.parseInt(size);
                    } catch (NumberFormatException e) {
                        throw new Exception("invalid agent.pool, expecting an integer");
                    }
                    if (intSize < 0) {
                        throw new Exception("invalid agent.pool, should not be negative");
                    }
                    poolSize = intSize;
                } else if (line.startsWith("agent.gateway.pac.listen ")) {
                    String val = line.substring("agent.gateway.pac.listen ".length()).trim();
                    int port;
                    try {
                        port = Integer.parseInt(val);
                    } catch (NumberFormatException e) {
                        throw new Exception("invalid agent.gateway.pac.listen, the port is invalid");
                    }
                    pacServerPort = port;
                } else if (line.startsWith("agent.auto-sign ")) {
                    line = line.substring("agent.auto-sign ".length());
                    var args = Arrays.stream(line.split(" ")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
                    if (args.isEmpty()) {
                        continue;
                    }
                    if (args.size() != 2 && args.size() != 3) {
                        throw new Exception("agent.auto-sign should take exactly two arguments");
                    }
                    autoSignCert = Utils.filename(args.get(0));
                    autoSignKey = Utils.filename(args.get(1));
                    if (!new File(autoSignCert).isFile()) throw new Exception("agent.auto-sign cert is not a file");
                    if (!new File(autoSignKey).isFile()) throw new Exception("agent.auto-sign key is not a file");
                    if (args.size() == 3) {
                        autoSignWorkingDirectory = new File(Utils.filename(args.get(2)));
                        if (!autoSignWorkingDirectory.isDirectory()) {
                            throw new Exception("agent.auto-sign tempDir is not a directory");
                        }
                    } else {
                        // allocate the temporary directory for auto signing
                        autoSignWorkingDirectory = Files.createTempDirectory("vpws-agent-auto-sign").toFile();
                        autoSignWorkingDirectory.deleteOnExit();
                    }
                } else if (line.startsWith("proxy.https-relay.domain.merge ")) {
                    String val = line.substring("proxy.https-relay.domain.merge ".length()).trim();
                    switch (val) {
                        case "on":
                            proxyHttpsRelayDomainMerge = true;
                            break;
                        case "off":
                            proxyHttpsRelayDomainMerge = false;
                            break;
                        default:
                            throw new Exception("invalid value for proxy.https-relay.domain.merge: " + val);
                    }
                } else if (line.startsWith("proxy.server.list.start")) {
                    step = 1; // retrieving server list
                    if (!line.equals("proxy.server.list.start")) {
                        String alias = line.substring("proxy.server.list.start".length()).trim();
                        if (alias.split(" ").length > 1)
                            throw new Exception("symbol cannot contain spaces");
                        currentAlias = alias;
                    }
                } else if (line.startsWith("proxy.domain.list.start")) {
                    step = 2;
                    if (!line.equals("proxy.domain.list.start")) {
                        String alias = line.substring("proxy.domain.list.start".length()).trim();
                        if (alias.split(" ").length > 1)
                            throw new Exception("symbol cannot contain spaces");
                        currentAlias = alias;
                    }
                } else if (line.startsWith("proxy.resolve.list.start")) {
                    step = 3;
                    if (!line.equals("proxy.resolve.list.start")) {
                        String alias = line.substring("proxy.resolve.list.start".length()).trim();
                        if (alias.split(" ").length > 1)
                            throw new Exception("symbol cannot contain spaces");
                        currentAlias = alias;
                    }
                } else if (line.startsWith("no-proxy.domain.list.start")) {
                    step = 4;
                    if (!line.equals("no-proxy.domain.list.start")) {
                        String alias = line.substring("no-proxy.domain.list.start".length()).trim();
                        if (alias.split(" ").length > 1)
                            throw new Exception("symbol cannot contain spaces");
                        currentAlias = alias;
                    }
                } else if (line.equals("https-relay.domain.list.start")) {
                    step = 5;
                } else if (line.equals("agent.https-relay.cert-key.list.start")) {
                    step = 6;
                } else if (line.equals("proxy.https-relay.domain.list.start")) {
                    step = 7;
                } else {
                    throw new Exception("unknown line: " + line);
                }
            } else if (step == 1) {
                if (line.equals("proxy.server.list.end")) {
                    step = 0; // return to normal state
                    currentAlias = null;
                    continue;
                }
                if (!line.startsWith("websocks://") && !line.startsWith("websockss://")
                    && !line.startsWith("websocks:kcp://") && !line.startsWith("websockss:kcp://")) {
                    throw new Exception("unknown protocol: " + line);
                }

                boolean useSSL = line.startsWith("websockss");
                boolean useKCP = line.contains(":kcp://");
                // format line
                if (useSSL) {
                    if (useKCP) {
                        line = line.substring("websockss:kcp://".length());
                    } else {
                        line = line.substring("websockss://".length());
                    }
                } else {
                    if (useKCP) {
                        line = line.substring("websocks:kcp://".length());
                    } else {
                        line = line.substring("websocks://".length());
                    }
                }

                String program = null;
                int programPort = 0;
                {
                    String[] split = line.split(" ");
                    if (split.length > 1) {
                        line = split[0];
                        StringBuilder sb = new StringBuilder(split[1]);
                        for (int i = 2; i < split.length; ++i) {
                            sb.append(" ").append(split[i]);
                        }
                        program = sb.toString();
                        program = program.replace("~", Utils.homedir());
                        programPort = (int) (30000 + 10000 * Math.random());
                        program = program.replace("$LOCAL_PORT", "" + programPort);
                    }
                }

                int colonIdx = line.lastIndexOf(':');
                if (colonIdx == -1)
                    throw new Exception("invalid address:port for proxy.server.list: " + line);
                String hostPart = line.substring(0, colonIdx);
                String portPart = line.substring(colonIdx + 1);
                if (hostPart.isEmpty())
                    throw new Exception("invalid host: " + line);
                int port;
                try {
                    port = Integer.parseInt(portPart);
                } catch (NumberFormatException e) {
                    throw new Exception("invalid port: " + line);
                }
                if (port < 1 || port > 65535) {
                    throw new Exception("invalid port: " + line);
                }

                if (program != null) {
                    program = program.replace("$SERVER_IP", hostPart);
                    program = program.replace("$SERVER_PORT", portPart);

                    final var finalProgram = program;

                    System.out.println("running program: [" + program + "]");
                    Process p = Utils.runSubProcess(program);
                    p.onExit().thenAccept(pp -> System.err.println("sub process [" + finalProgram + "] exits with " + pp.exitValue()));
                    Utils.proxyProcessOutput(p);
                }

                ServerGroup.ServerHandle handle;
                if (program != null) {
                    InetAddress inet = Utils.l3addr(new byte[]{127, 0, 0, 1});
                    handle = getGroup(currentAlias).add(line, new InetSocketAddress(inet, programPort), 10);
                } else if (Utils.isIpLiteral(hostPart)) {
                    InetAddress inet = Utils.l3addr(hostPart);
                    handle = getGroup(currentAlias).add(line, new InetSocketAddress(inet, port), 10);
                } else {
                    BlockCallback<InetAddress, IOException> cb = new BlockCallback<>();
                    Resolver.getDefault().resolveV4(hostPart, cb);
                    InetAddress inet = cb.block();
                    handle = getGroup(currentAlias).add(line, hostPart, new InetSocketAddress(inet, port), 10);
                }

                // init streamed fds
                Map<SelectorEventLoop, H2StreamedClientFDs> fds = new HashMap<>();
                if (useKCP) {
                    {
                        // build fds map
                        Set<NetEventLoop> set = new HashSet<>();
                        while (true) {
                            NetEventLoop l = workerLoopGroup.next();
                            if (!set.add(l)) {
                                // all loops visited
                                break;
                            }
                            // build for this remote server
                            KCPFDs kcpFDs = KCPFDs.getClientDefault();
                            H2StreamedClientFDs h2sFDs = new H2StreamedClientFDs(kcpFDs, l.getSelectorEventLoop(),
                                handle.server);
                            fds.put(l.getSelectorEventLoop(), h2sFDs);
                        }
                    }
                }
                // this will be used when connection establishes to remote
                // in WebSocksProxyAgentConnectorProvider.java
                // also in HttpDNSServer.java
                handle.data = new SharedData(useSSL, useKCP, fds);
            } else if (step == 2) {
                if (line.equals("proxy.domain.list.end")) {
                    step = 0;
                    currentAlias = null;
                    continue;
                }
                getDomainList(currentAlias).add(formatDomainChecker(line));
            } else if (step == 3) {
                if (line.equals("proxy.resolve.list.end")) {
                    step = 0;
                    currentAlias = null;
                    continue;
                }
                getProxyResolveList(currentAlias).add(formatDomainChecker(line));
            } else if (step == 4) {
                if (line.equals("no-proxy.domain.list.end")) {
                    step = 0;
                    currentAlias = null;
                    continue;
                }
                getNoProxyDomainList(currentAlias).add(formatDomainChecker(line));
            } else if (step == 5) {
                if (line.equals("https-relay.domain.list.end")) {
                    step = 0;
                    continue;
                }
                httpsRelayDomains.add(formatDomainChecker(line));
            } else if (step == 6) {
                if (line.equals("agent.https-relay.cert-key.list.end")) {
                    step = 0;
                    continue;
                }
                var ls = Arrays.stream(line.split(" ")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
                if (ls.isEmpty())
                    continue;
                httpsRelayCertKeyFiles.add(ls);
            } else {
                //noinspection ConstantConditions
                assert step == 7;
                if (line.equals("proxy.https-relay.domain.list.end")) {
                    step = 0;
                    continue;
                }
                proxyHttpsRelayDomains.add(formatDomainChecker(line));
            }
        }

        // check for variables must present
        if (user == null || pass == null)
            throw new Exception("proxy.server.auth not present");
        // merge lists
        if (proxyHttpsRelayDomainMerge) {
            for (List<DomainChecker> ls : domains.values()) {
                proxyHttpsRelayDomains.addAll(ls);
            }
        }
        // check for https relay
        if (!httpsRelayCertKeyFiles.isEmpty()) {
            int idx = 0;
            for (List<String> files : httpsRelayCertKeyFiles) {
                String[] certs = new String[files.size() - 1];
                for (int i = 0; i < certs.length; ++i) {
                    certs[i] = files.get(i);
                }
                String key = files.get(files.size() - 1);
                CertKey certKey = CertKeyHolder.readFile("agent.https-relay.cert-key." + idx, certs, key);
                httpsRelayCertKeys.add(certKey);
                ++idx;
            }
        } else if (autoSignCert == null) {
            if (!httpsRelayDomains.isEmpty()) {
                throw new Exception("agent.https-relay.cert-key.list is empty and auto-sign is disabled, but https-relay.domain.list is not empty");
            }
            if (directRelay) {
                throw new Exception("agent.https-relay.cert-key.list is empty and auto-sign is disabled, but agent.direct-relay is enabled");
            }
            if (proxyRelay != null && proxyRelay) {
                throw new Exception("agent.https-relay.cert-key.list is empty and auto-sign is disabled, but agent.proxy-relay is enabled");
            }
        }
        // check for direct relay switch
        if (!directRelay) {
            if (!httpsRelayDomains.isEmpty()) {
                throw new Exception("agent.direct-relay is disabled, but https-relay.domain.list is not empty");
            }
            if (!proxyHttpsRelayDomains.isEmpty() || proxyHttpsRelayDomainMerge) {
                throw new Exception("agent.direct-relay is disabled, but proxy.https-relay.domain.list is not empty");
            }
        }
        // check for consistency of server list and domain list
        for (String k : domains.keySet()) {
            if (!servers.containsKey(k))
                throw new Exception(k + " is defined in domain list, but not in server list");
        }
        // check for consistency of server list and resolve list
        for (String k : proxyResolves.keySet()) {
            if (!servers.containsKey(k))
                throw new Exception(k + " is defined in resolve list, but not in server list");
        }
        // check for consistency of server list and no-proxy list
        for (String k : noProxyDomains.keySet()) {
            if (!servers.containsKey(k))
                throw new Exception(k + " is defined in resolve list, but not in server list");
        }
        // check for pac server
        if (pacServerPort != 0) {
            if (socks5ListenPort == 0 && httpConnectListenPort == 0) {
                throw new Exception("pac server is defined, but neither socks5-server nor http-connect-server is defined");
            }
        }
        // check for ss
        if (ssListenPort != 0 && ssPassword.isEmpty()) {
            throw new Exception("ss is enabled by agent.ss.listen, but agent.ss.password is not set");
        }
        // load cert-key(s) in autoSignWorkingDirectory
        if (autoSignWorkingDirectory != null) {
            File[] files = autoSignWorkingDirectory.listFiles();
            if (files == null) {
                throw new Exception("cannot list files under " + autoSignWorkingDirectory.getAbsolutePath());
            }
            Set<String> crt = new HashSet<>();
            Set<String> key = new HashSet<>();
            for (File f : files) {
                String name = f.getName();
                String domain = name.substring(0, name.length() - 4);
                if (name.endsWith(".key")) {
                    key.add(name);
                    if (!crt.contains(domain)) {
                        continue;
                    }
                } else if (name.endsWith(".crt")) {
                    crt.add(domain);
                    if (!key.contains(domain)) {
                        continue;
                    }
                } else {
                    continue;
                }
                loadCertKeyInAutoSignWorkingDirectory(autoSignWorkingDirectory, domain);
            }
        }
    }

    private void loadCertKeyInAutoSignWorkingDirectory(File autoSignWorkingDirectory, String domain) throws Exception {
        String crt = Path.of(autoSignWorkingDirectory.getAbsolutePath(), domain + ".crt").toString();
        String key = Path.of(autoSignWorkingDirectory.getAbsolutePath(), domain + ".key").toString();
        CertKey ck = CertKeyHolder.readFile("agent.auto-sign." + domain, new String[]{crt}, key);
        addCertKeyToAllLists(ck);
    }

    private void addCertKeyToAllLists(CertKey ck) {
        httpsRelayCertKeys.add(ck);
    }

    private DomainChecker formatDomainChecker(String line) throws Exception {
        if (line.startsWith(":")) {
            String portStr = line.substring(1);
            int port;
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                throw new Exception("invalid port rule: " + portStr);
            }
            return new PortChecker(port);
        } else if (line.startsWith("/") && line.endsWith("/")) {
            String regexp = line.substring(1, line.length() - 1);
            return new PatternDomainChecker(Pattern.compile(regexp));
        } else if (line.startsWith("[") && line.endsWith("]")) {
            String abpfile = line.substring(1, line.length() - 1).trim();
            String content;
            if (abpfile.contains("://")) {
                Logger.alert("getting abp from " + abpfile);
                String protocolAndHostAndPort;
                String uri;
                {
                    String protocol;
                    String hostAndPortAndUri;
                    if (abpfile.startsWith("http://")) {
                        protocol = "http://";
                        hostAndPortAndUri = abpfile.substring("http://".length());
                    } else if (abpfile.startsWith("https://")) {
                        protocol = "https://";
                        hostAndPortAndUri = abpfile.substring("https://".length());
                    } else {
                        throw new Exception("unknown protocol in " + abpfile);
                    }
                    if (hostAndPortAndUri.contains("/")) {
                        protocolAndHostAndPort = protocol + hostAndPortAndUri.substring(0, hostAndPortAndUri.indexOf("/"));
                        uri = hostAndPortAndUri.substring(hostAndPortAndUri.indexOf("/"));
                    } else {
                        protocolAndHostAndPort = hostAndPortAndUri;
                        uri = "/";
                    }
                }
                BlockCallback<HttpResponse, IOException> cb = new BlockCallback<>();
                HttpClient.to(protocolAndHostAndPort).get(uri).send((err, response) -> {
                    if (err != null) {
                        cb.failed(err);
                    } else {
                        cb.succeeded(response);
                    }
                });
                HttpResponse resp;
                try {
                    resp = cb.block();
                } catch (IOException e) {
                    throw new IOException("requesting " + abpfile + " failed", e);
                }
                if (resp.status() != 200) {
                    throw new IOException("requesting " + abpfile + " failed, response status not 200: " + resp.status());
                }
                if (resp.body() == null) {
                    throw new IOException("requesting " + abpfile + " failed, no response body");
                }
                content = new String(resp.body().toJavaArray());
                content = Arrays.stream(content.split("\n")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.joining());
            } else {
                abpfile = Utils.filename(abpfile);
                try (FileReader fileABP = new FileReader(abpfile)) {
                    StringBuilder sb = new StringBuilder();
                    BufferedReader br2 = new BufferedReader(fileABP);
                    String line2;
                    while ((line2 = br2.readLine()) != null) {
                        sb.append(line2.trim());
                    }
                    content = sb.toString();
                }
            }

            ABP abp = new ABP(true);
            abp.addBase64(content);
            return new ABPDomainChecker(abp);
        } else {
            return new SuffixDomainChecker(line);
        }
    }
}
