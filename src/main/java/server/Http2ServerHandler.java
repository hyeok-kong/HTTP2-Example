package server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.codec.http2.*;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.util.CharsetUtil;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Http2ServerHandler extends Http2ConnectionHandler implements Http2FrameListener {

    private final Map<Integer, ByteBuf> dataBufferMap = new ConcurrentHashMap<>();
    private final Map<Integer, Http2Headers> headersMap = new ConcurrentHashMap<>();

    // Default constructor delegates to private one, ensuring super() is first
    public Http2ServerHandler() {
        this(new DefaultHttp2Connection(true), new DefaultHttp2FrameWriter(), new DefaultHttp2FrameReader());
    }

    private Http2ServerHandler(Http2Connection connection, Http2FrameWriter writer, Http2FrameReader reader) {
        super(
            new DefaultHttp2ConnectionDecoder(connection, new DefaultHttp2ConnectionEncoder(connection, writer), reader),
            new DefaultHttp2ConnectionEncoder(connection, writer),
            new Http2Settings()
        );
        // Register this as the frame listener after super
        decoder().frameListener(this);
    }

    @Override
    public void onHeadersRead(ChannelHandlerContext ctx, int streamId,
        Http2Headers headers, int padding,
        boolean endOfStream) throws Http2Exception {
        handleHeaders(ctx, streamId, headers, endOfStream);
    }

    @Override
    public void onHeadersRead(ChannelHandlerContext ctx, int streamId,
        Http2Headers headers, int streamDependency,
        short weight, boolean exclusive,
        int padding, boolean endOfStream) throws Http2Exception {
        handleHeaders(ctx, streamId, headers, endOfStream);
    }

    private void handleHeaders(ChannelHandlerContext ctx, int streamId,
        Http2Headers headers, boolean endOfStream) throws Http2Exception {
        headersMap.put(streamId, headers);
        System.out.println("[LOG] Headers received on stream " + streamId + ": path=" + headers.path());
        if (endOfStream) {
            handleRequest(ctx, streamId, headers, Unpooled.buffer(0));
        } else {
            dataBufferMap.put(streamId, Unpooled.buffer());
        }
    }

    @Override
    public int onDataRead(ChannelHandlerContext ctx, int streamId,
        ByteBuf data, int padding,
        boolean endOfStream) throws Http2Exception {
        ByteBuf buf = dataBufferMap.computeIfAbsent(streamId, id -> Unpooled.buffer());
        buf.writeBytes(data.retainedSlice());

        if (endOfStream) {
            Http2Headers h = headersMap.remove(streamId);
            ByteBuf full = dataBufferMap.remove(streamId);
            handleRequest(ctx, streamId, h, full);
        }

        int consumed = data.readableBytes() + padding;
        data.release();
        return consumed;
    }

    @Override public void onPriorityRead(ChannelHandlerContext ctx, int streamId,
        int streamDependency, short weight,
        boolean exclusive) throws Http2Exception {}
    @Override public void onRstStreamRead(ChannelHandlerContext ctx, int streamId,
        long errorCode) throws Http2Exception {}
    @Override public void onSettingsAckRead(ChannelHandlerContext ctx) throws Http2Exception {}
    @Override public void onSettingsRead(ChannelHandlerContext ctx, Http2Settings settings) throws Http2Exception {}
    @Override public void onPingRead(ChannelHandlerContext ctx, long data) throws Http2Exception {}
    @Override public void onPingAckRead(ChannelHandlerContext ctx, long data) throws Http2Exception {}
    @Override public void onPushPromiseRead(ChannelHandlerContext ctx, int streamId,
        int promisedStreamId, Http2Headers headers,
        int padding) throws Http2Exception {}
    @Override public void onGoAwayRead(ChannelHandlerContext ctx, int lastStreamId,
        long errorCode, ByteBuf debugData) throws Http2Exception {}
    @Override public void onWindowUpdateRead(ChannelHandlerContext ctx, int streamId,
        int windowSizeIncrement) throws Http2Exception {}
    @Override public void onUnknownFrame(ChannelHandlerContext ctx, byte frameType,
        int streamId, Http2Flags flags,
        ByteBuf payload) throws Http2Exception {}

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof HttpServerUpgradeHandler.UpgradeEvent) {
            HttpServerUpgradeHandler.UpgradeEvent up = (HttpServerUpgradeHandler.UpgradeEvent) evt;
            FullHttpRequest req = up.upgradeRequest();
            Http2Headers h2 = new DefaultHttp2Headers()
                .method(req.method().asciiName())
                .path(req.uri())
                .scheme("http")
                .authority(req.headers().get(HttpHeaderNames.HOST, ""));
            onHeadersRead(ctx, 1, h2, 0, true);
        }
        super.userEventTriggered(ctx, evt);
    }

    private void handleRequest(ChannelHandlerContext ctx, int streamId,
        Http2Headers headers, ByteBuf data) {
        String path = headers.path().toString();
        String body = data.toString(CharsetUtil.UTF_8);
        System.out.println("[LOG] Request received: path=" + path + ", body=" + body);
        String resp = "Received " + body.toUpperCase() + "!!!";
        ByteBuf content = ctx.alloc().buffer().writeBytes(resp.getBytes(CharsetUtil.UTF_8));

        encoder().writeHeaders(ctx, streamId,
            new DefaultHttp2Headers().status("200"), 0, false, ctx.newPromise());
        encoder().writeData(ctx, streamId, content, 0, true, ctx.newPromise());
        ctx.flush();
    }
}
