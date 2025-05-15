package client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http2.*;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.buffer.Unpooled;
import io.netty.util.CharsetUtil;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.util.concurrent.Future;

public class Http2Client {

    // ─────────────────────────────────────────────────────────────────────────────
    // 1) 최상단에 Path, HTTP Method, 호스트/포트, Body 상수 선언
    // ─────────────────────────────────────────────────────────────────────────────
    private static final String HOST   = "127.0.0.1";
    private static final int    PORT   = 8080;
    private static final HttpMethod METHOD = HttpMethod.POST;
    private static final String PATH   = "/api/v1/resource";
    private static final String BODY   = "{\"key\":\"value\"}";  // 전송할 JSON 바디

    public static void main(String[] args) throws Exception {
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            Bootstrap b = new Bootstrap();
            b.group(workerGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        p.addLast(new LoggingHandler(LogLevel.DEBUG));

                        // HTTP/2 프레임 코덱 설정 (cleartext h2c)
                        Http2FrameCodec frameCodec = Http2FrameCodecBuilder.forClient()
                            .initialSettings(Http2Settings.defaultSettings())
                            .build();
                        p.addLast(frameCodec);

                        // 멀티플렉싱 핸들러: 새로운 스트림 채널 초기화
                        p.addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                            @Override
                            protected void initChannel(Channel ch) {
                                ch.pipeline().addLast(new SimpleChannelInboundHandler<Http2HeadersFrame>() {
                                    @Override
                                    protected void channelRead0(ChannelHandlerContext ctx, Http2HeadersFrame headersFrame) {
                                        System.out.println("Received headers: " + headersFrame.headers());
                                    }
                                });
                                ch.pipeline().addLast(new SimpleChannelInboundHandler<Http2DataFrame>() {
                                    @Override
                                    protected void channelRead0(ChannelHandlerContext ctx, Http2DataFrame dataFrame) {
                                        System.out.println("Received data: " + dataFrame.content().toString(CharsetUtil.UTF_8));
                                        if (dataFrame.isEndStream()) {
                                            ctx.channel().close();
                                        }
                                    }
                                });
                            }
                        }));

                        // 부모 채널 활성화 시점에 스트림 오픈 및 요청 전송
                        p.addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelActive(ChannelHandlerContext ctx) {
                                System.out.println("Parent channel active, opening HTTP/2 stream and sending request...");

                                Http2StreamChannelBootstrap sb = new Http2StreamChannelBootstrap(ctx.channel())
                                    .handler(new ChannelInitializer<Http2StreamChannel>() {
                                        @Override
                                        protected void initChannel(Http2StreamChannel ch) {
                                            // 자식 스트림 채널은 위에서 멀티플렉싱 핸들러가 초기화
                                        }
                                    });

                                sb.open().addListener((Future<Http2StreamChannel> openFuture) -> {
                                    if (openFuture.isSuccess()) {
                                        Http2StreamChannel streamChannel = openFuture.getNow();

                                        // 요청 헤더 프레임 전송 (endStream=false)
                                        Http2Headers headers = new DefaultHttp2Headers()
                                            .method(METHOD.asciiName())
                                            .scheme("http")
                                            .authority(HOST + ":" + PORT)
                                            .path(PATH)
                                            .add("content-type", "application/json");
                                        streamChannel.write(new DefaultHttp2HeadersFrame(headers, false));

                                        // 요청 바디 프레임 전송 및 스트림 종료 (endStream=true)
                                        streamChannel.write(new DefaultHttp2DataFrame(
                                            Unpooled.copiedBuffer(BODY, CharsetUtil.UTF_8), true
                                        ));

                                        // 버퍼에 쌓인 프레임을 모두 플러시
                                        streamChannel.flush();

                                        // 수동으로 읽기 요청하여 응답 수신 활성화
                                        streamChannel.read();

                                    } else {
                                        openFuture.cause().printStackTrace();
                                        ctx.channel().close();
                                    }
                                });
                            }
                        });
                    }
                });

            // 서버 연결 및 종료 대기
            Channel parentChannel = b.connect(HOST, PORT).syncUninterruptibly().channel();
            parentChannel.closeFuture().syncUninterruptibly();
        } finally {
            workerGroup.shutdownGracefully();
        }
    }
}
