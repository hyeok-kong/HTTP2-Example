package client2;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http2.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

public class Http2PureH2cClient {
    private final String host;
    private final int port;
    private Channel channel;
    private EventLoopGroup group;

    public Http2PureH2cClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void start() {
        group = new NioEventLoopGroup();
        Bootstrap b = new Bootstrap();
        b.group(group)
            .channel(NioSocketChannel.class)
            .option(ChannelOption.SO_KEEPALIVE, true)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ChannelPipeline p = ch.pipeline();
                    // 1) HTTP/2 프레임 코덱 (prior-knowledge h2c)
                    p.addLast(Http2FrameCodecBuilder.forClient().build());
                    // 2) 멀티플렉싱: 각 스트림별 Channel 생성
                    p.addLast(new Http2MultiplexHandler(new StreamHandler()));
                }
            });

        channel = b.connect(host, port).syncUninterruptibly().channel();
        System.out.println("Connected with HTTP/2 prior-knowledge (h2c) to " + host + ":" + port);
    }

    /**
     * 동일 커넥션에서 비동기로 count 개의 POST 요청(헤더+바디)을 보냅니다.
     * 각 스트림별 로그를 System.out 으로 출력합니다.
     */
    public void sendMultiplePosts(String path, String jsonBody, int count) {
        Http2StreamChannelBootstrap bootstrap = new Http2StreamChannelBootstrap(channel);
        System.out.println("Sending " + count + " POST requests with body to " + path);

        for (int i = 0; i < count; i++) {
            final int idx = i;
            bootstrap.open().addListener(
                (GenericFutureListener<Future<Channel>>) future -> {
                    if (!future.isSuccess()) {
                        System.err.println("[Stream-" + idx + "] open failed: " + future.cause());
                        return;
                    }
                    Channel streamCh = future.getNow();
                    System.out.println("[Stream-" + idx + "] opened");

                    // 1) HEADERS only (endStream=false)
                    Http2Headers headers = new DefaultHttp2Headers()
                        .method("POST")
                        .scheme("http")
                        .authority(host + ":" + port)
                        .path(path + "?req=" + idx)
                        .add("content-type", "application/json")
                        .add("content-length", String.valueOf(jsonBody.getBytes().length));
                    streamCh.write(new DefaultHttp2HeadersFrame(headers, false));
                    System.out.println("[Stream-" + idx + "] sent HEADERS");

                    // 2) DATA (endStream=true)
                    ByteBuf content = Unpooled.copiedBuffer(jsonBody.getBytes());
                    streamCh.writeAndFlush(new DefaultHttp2DataFrame(content, true));
                    System.out.println("[Stream-" + idx + "] sent DATA and endStream");
                }
            );
        }
    }

    public void stop() {
        if (channel != null) {
            channel.close();
            System.out.println("Main channel closed");
        }
        if (group != null) {
            group.shutdownGracefully();
            System.out.println("EventLoopGroup shut down");
        }
    }

    public static void main(String[] args) throws InterruptedException {
        // 1) 접속할 호스트/포트
        Http2PureH2cClient client =
            new Http2PureH2cClient("172.27.112.1", 30083);
        client.start();

        // 2) curl -d 로 보낸 JSON 페이로드
        String json = "{\"test1\":\"hello123\",\"test2\":\"world\"}";

        // 3) 멀티플렉싱 테스트: 여기서는 1개의 스트림으로 동일 요청을 보냄
        client.sendMultiplePosts("/test", json, 1);

        // 충분히 대기
        Thread.sleep(5000);
        client.stop();
    }

    /** 스트림별 응답 처리 핸들러 */
    private static class StreamHandler extends ChannelInitializer<Channel> {
        @Override
        protected void initChannel(Channel ch) {
            final String streamId = ch.id().asShortText();
            ch.pipeline().addLast(new SimpleChannelInboundHandler<Http2Frame>() {
                @Override
                protected void channelRead0(ChannelHandlerContext ctx, Http2Frame frame) {
                    if (frame instanceof Http2HeadersFrame) {
                        Http2HeadersFrame hf = (Http2HeadersFrame) frame;
                        System.out.println("[Stream-" + streamId + "] RECEIVED_HEADERS: " + hf.headers());
                        if (hf.isEndStream()) {
                            System.out.println("[Stream-" + streamId + "] END_OF_STREAM (headers-only)");
                            ctx.close();
                        }
                    } else if (frame instanceof Http2DataFrame) {
                        Http2DataFrame df = (Http2DataFrame) frame;
                        String body = df.content().toString(io.netty.util.CharsetUtil.UTF_8);
                        System.out.println("[Stream-" + streamId + "] RECEIVED_DATA: " + body);
                        if (df.isEndStream()) {
                            System.out.println("[Stream-" + streamId + "] END_OF_STREAM (data)");
                            ctx.close();
                        }
                    }
                }
                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                    System.err.println("[Stream-" + streamId + "] error:");
                    cause.printStackTrace();
                    ctx.close();
                }
            });
        }
    }
}
