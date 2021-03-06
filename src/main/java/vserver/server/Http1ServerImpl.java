package vserver.server;

import vjson.JSON;
import vproxy.app.Application;
import vproxy.connection.NetEventLoop;
import vproxy.connection.ServerSock;
import vproxy.http.HttpContext;
import vproxy.http.HttpProtocolHandler;
import vproxy.processor.http1.entity.Chunk;
import vproxy.processor.http1.entity.Header;
import vproxy.processor.http1.entity.Request;
import vproxy.processor.http1.entity.Response;
import vproxy.protocol.ProtocolHandlerContext;
import vproxy.protocol.ProtocolServerConfig;
import vproxy.protocol.ProtocolServerHandler;
import vproxy.selector.SelectorEventLoop;
import vproxy.util.*;
import vserver.*;
import vserver.route.WildcardRoute;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.stream.Collectors;

import static vserver.HttpMethod.ALL_METHODS;

public class Http1ServerImpl implements HttpServer {
    private boolean started = false;
    private boolean closed = false;
    private final Map<HttpMethod, Tree<Route, RoutingHandler>> routes = new HashMap<>(HttpMethod.values().length) {{
        for (HttpMethod m : HttpMethod.values()) {
            put(m, new Tree<>());
        }
    }};
    private NetEventLoop loop;
    private final boolean noInputLoop;
    private ServerSock server;

    public Http1ServerImpl() {
        this(null);
    }

    public Http1ServerImpl(NetEventLoop loop) {
        this.loop = loop;
        noInputLoop = loop == null;
    }

    private void record(Tree<Route, RoutingHandler> tree, Route route, RoutingHandler handler) {
        if (route == null) {
            tree.leaf(handler);
            return;
        }
        var last = tree.lastBranch();
        if (last != null && last.data.currentSame(route)) {
            // can use the last node
            record(last, route.next(), handler);
            return;
        }
        // must be new route
        var br = tree.branch(route);
        record(br, route.next(), handler);
    }

    private void record(HttpMethod[] methods, Route route, RoutingHandler handler) {
        for (HttpMethod m : methods) {
            record(routes.get(m), route, handler);
        }
    }

    @Override
    public HttpServer handle(HttpMethod[] methods, Route route, RoutingHandler handler) {
        if (started) {
            throw new IllegalStateException("This http server is already started");
        }
        record(methods, route, handler);
        return this;
    }

    @Override
    public void listen(InetSocketAddress addr) throws IOException {
        if (started) {
            throw new IllegalStateException("This http server is already started");
        }
        started = true;
        record(ALL_METHODS, Route.create("/*"), this::handle404);

        initLoop();

        server = ServerSock.create(addr);
        ProtocolServerHandler.apply(loop, server,
            new ProtocolServerConfig().setInBufferSize(4096).setOutBufferSize(4096),
            new HttpProtocolHandler(true) {
                @Override
                protected void request(ProtocolHandlerContext<HttpContext> ctx) {
                    handle(ctx);
                }
            });
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;

        if (noInputLoop) {
            // should stop the event loop because it's created from inside
            if (loop != null) {
                try {
                    loop.getSelectorEventLoop().close();
                } catch (IOException e) {
                    Logger.shouldNotHappen("got error when closing the event loop", e);
                }
            }
        }
        if (server != null) {
            server.close();
        }
    }

    private void handle404(RoutingContext ctx) {
        ctx.response()
            .status(404)
            .end(ByteArray.from(("Cannot " + ctx.method() + " " + ctx.uri() + "\r\n").getBytes()));
    }

    private void initLoop() throws IOException {
        if (loop != null) {
            return;
        }
        loop = new NetEventLoop(SelectorEventLoop.open());
        loop.getSelectorEventLoop().loop(Thread::new);
    }

    private void sendResponse(ProtocolHandlerContext<HttpContext> _pctx, Response response) {
        if (response.headers == null) {
            response.headers = new ArrayList<>(2);
            // may add
            // x-powered-by
            // content-length
        }
        boolean needAddServerId = true;
        for (Header h : response.headers) {
            if (h.key.equalsIgnoreCase("x-powered-by")) {
                needAddServerId = false;
                break;
            }
        }
        if (needAddServerId) {
            response.headers.add(new Header("X-Powered-By", "vproxy/" + Application.VERSION));
        }
        // fill in default values
        if (response.version == null) {
            response.version = "HTTP/1.1";
        }
        if (response.statusCode == 0) {
            response.statusCode = 200;
            response.reason = "OK";
        }
        if (response.body != null) {
            response.headers.add(new Header("Content-Length", Integer.toString(response.body.length())));
        }
        _pctx.write(response.toByteArray().toJavaArray());
    }

    private void handle(ProtocolHandlerContext<HttpContext> _pctx) {
        Request request = _pctx.data.result;
        RoutingContext[] ctx = new RoutingContext[1];
        {
            final HttpMethod method;
            final Map<String, String> headers = new HashMap<>();
            final String uri = unescape(request.uri);
            final Map<String, String> query = new HashMap<>();
            final ByteArray body;
            final HttpResponse response;
            final HandlerChain chain;

            final List<String> paths;
            { // paths and query
                if (uri == null) {
                    Response resp = new Response();
                    resp.statusCode = 400;
                    resp.reason = "Bad Request";
                    resp.body = ByteArray.from("Bad Request: invalid uri\r\n".getBytes());
                    sendResponse(_pctx, resp);
                    return;
                }
                String path = uri;
                String queryPart = "";
                if (path.contains("?")) {
                    queryPart = path.substring(path.indexOf('?') + 1);
                    path = path.substring(0, path.indexOf('?'));
                }
                paths = Arrays.stream(path.split("/")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
                var queryList = Arrays.stream(queryPart.split("&")).filter(s -> !s.isBlank()).collect(Collectors.toList());
                for (var qkv : queryList) {
                    if (qkv.indexOf('=') == -1) {
                        query.put(qkv, "");
                    } else {
                        int idx = qkv.indexOf('=');
                        query.put(qkv.substring(0, idx), qkv.substring(idx + 1));
                    }
                }
            }

            { // method
                try {
                    method = HttpMethod.valueOf(request.method);
                } catch (RuntimeException e) {
                    Response resp = new Response();
                    resp.statusCode = 400;
                    resp.reason = "Bad Request";
                    resp.body = ByteArray.from("Bad Request: invalid method\r\n".getBytes());
                    sendResponse(_pctx, resp);
                    return;
                }
            }
            { // uri
                if (!uri.startsWith("/")) {
                    Response resp = new Response();
                    resp.statusCode = 400;
                    resp.reason = "Bad Request";
                    resp.body = ByteArray.from("Bad Request: invalid uri\r\n".getBytes());
                    sendResponse(_pctx, resp);
                    return;
                }
            }
            { // headers
                if (request.headers != null) {
                    for (Header h : request.headers) {
                        headers.put(h.key.toLowerCase(), h.value);
                    }
                }
                if (request.trailers != null) {
                    for (Header h : request.trailers) {
                        headers.put(h.key.toLowerCase(), h.value);
                    }
                }
            }
            { // body
                if (request.body != null) {
                    body = request.body;
                } else if (request.chunks != null) {
                    ByteArray foo = null;
                    for (Chunk c : request.chunks) {
                        if (foo == null) {
                            foo = c.content;
                        } else {
                            foo = foo.concat(c.content);
                        }
                    }
                    body = foo;
                } else {
                    body = null;
                }
            }
            { // response
                response = new HttpResponse() {
                    Response response = new Response();
                    boolean isEnd = false;

                    @Override
                    public HttpResponse status(int code, String msg) {
                        if (isEnd) {
                            throw new IllegalStateException("This response is already ended");
                        }
                        response.statusCode = code;
                        response.reason = msg;
                        return this;
                    }

                    @Override
                    public HttpResponse header(String key, String value) {
                        if (isEnd) {
                            throw new IllegalStateException("This response is already ended");
                        }
                        if (response.headers == null) {
                            response.headers = new LinkedList<>();
                        }
                        response.headers.add(new Header(key, value));
                        return this;
                    }

                    @Override
                    public void end(JSON.Instance inst) {
                        header("Content-Type", "application/json");
                        String ua = headers.get("user-agent");
                        if (ua != null && ua.startsWith("curl/")) {
                            // use pretty
                            end(inst.pretty() + "\r\n");
                        } else {
                            end(inst.stringify());
                        }
                    }

                    @Override
                    public void end(ByteArray body) {
                        if (isEnd) {
                            throw new IllegalStateException("This response is already ended");
                        }
                        isEnd = true;
                        response.body = body;
                        sendResponse(_pctx, response);
                    }
                };
            }
            { // chain
                var list = buildHandlerChain(routes.get(method), paths).iterator();
                chain = () -> {
                    var tup = list.next();
                    tup.left.forEach(pre -> pre.accept(ctx[0])); // pre process
                    tup.right.accept(ctx[0]);
                };
            }

            // build ctx
            ctx[0] = new RoutingContext(method, uri, query, headers, body, response, chain);
        }
        ctx[0].next();
    }

    private static String unescape(String uri) {
        byte[] input = uri.getBytes();
        int idx = 0;
        byte[] result = new byte[input.length];
        int state = 0; // 0 -> normal, 1 -> %[x]x, 2 -> %x[x]
        int a = 0;
        for (byte b : input) {
            char c = (char) b; // safe
            if (state == 0) {
                if (c == '%') {
                    state = 1;
                } else {
                    result[idx++] = b;
                }
            } else {
                int n;
                try {
                    n = Integer.parseInt("" + c, 16);
                } catch (NumberFormatException ignore) {
                    assert Logger.lowLevelDebug("escaped uri part not number: " + c);
                    return null;
                }
                if (state == 1) {
                    a = n;
                    state = 2;
                } else {
                    n = a * 16 + n;
                    result[idx++] = (byte) n;
                    state = 0;
                }
            }
        }
        if (state == 0) {
            return new String(result, 0, idx);
        } else {
            return null;
        }
    }

    private List<Tuple<List<RoutingHandler>, RoutingHandler>> buildHandlerChain(Tree<Route, RoutingHandler> tree, List<String> paths) {
        var ls = new LinkedList<Tuple<List<RoutingHandler>, RoutingHandler>>();
        var pre = Collections.<RoutingHandler>emptyList();
        if (paths.isEmpty()) {
            ls.add(new Tuple<>(Collections.singletonList(rctx -> {
            }), this::handle404));
        } else {
            buildHandlerChain(ls, pre, tree, paths, 0);
        }
        assert !ls.isEmpty(); // 404 handler would had already been added
        return ls;
    }

    private void buildHandlerChain(List<Tuple<List<RoutingHandler>, RoutingHandler>> ret,
                                   List<RoutingHandler> preHandlers,
                                   Tree<Route, RoutingHandler> tree,
                                   List<String> paths,
                                   int pathIdx) {
        if (pathIdx >= paths.size()) {
            return;
        }
        String path = paths.get(pathIdx);
        boolean isLast = pathIdx + 1 == paths.size();
        for (var br : tree.branches()) {
            RoutingHandler preHandler = rctx -> br.data.fill(rctx, path);
            if (br.data.match(path)) {
                var newPathIdx = pathIdx + 1;
                var newPreHandlers = new AppendingList<>(preHandlers, preHandler);

                if (isLast || br.data instanceof WildcardRoute) {
                    for (var h : br.leafData()) {
                        ret.add(new Tuple<>(newPreHandlers, h));
                    }
                }

                buildHandlerChain(ret, newPreHandlers, br, paths, newPathIdx);
            }
        }
    }
}
