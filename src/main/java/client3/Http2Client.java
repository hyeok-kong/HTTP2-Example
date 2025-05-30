package client3;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http2.*;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;

public class Http2Client {

    // 대상 서버 정보 및 요청 바디
    private static final String HOST = "172.27.112.1";
    private static final int PORT = 30083;
//    private static final String HOST = "127.0.0.1";
//    private static final int PORT = 8443;

    private static final String PATH1 = "/http2-test";
    private static final String PATH2 = "/test2";
    private static final String PATH3 = "/test3";

    private static final String METHOD = "Post";

    private static final String BODY2 =
        "<?xml version=\"1.0\" encoding=\"EUC-KR\"?>\n"
            + "<wstxns1:HTT2_MESSAGE\n"
            + "    xmlns:wstxns1=\"urn:anylink:HDCard.poc.http2\">\n"
            + "    <wstxns1:test1>good123</wstxns1:test1>\n"
            + "    <wstxns1:test2>job456</wstxns1:test2>\n"
            + "</wstxns1:HTT2_MESSAGE>";

    private static final String BODY1 = "{\"test1\":\"hello123\", \"test2\":\"world\"}";
    private static final String BODY3 = "fixedTest!Hello World!";


    private static final String[] paths = {PATH1, PATH2, /*PATH3*/};
    private static final String[] bodys = {BODY1, BODY2, /*BODY3*/};


    public static void main(String[] args) throws Exception {
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        Http2FrameCodec codec = Http2FrameCodecBuilder.forClient().build();
                        ch.pipeline().addLast(codec);
                        ch.pipeline().addLast(new Http2MultiplexHandler(new SimpleChannelInboundHandler<Http2Frame>() {
                            @Override protected void channelRead0(ChannelHandlerContext ctx, Http2Frame frame) {}
                        }));
                    }
                });

            System.out.println("create bootstrap");
            Channel parent = bootstrap.connect(HOST, PORT).sync().channel();
            System.out.println("h2c connected.");

            int numRequests = 1;  // 병렬 요청 개수
            for (int i = 1; i <= numRequests; i++) {
                int idx = i;
                // 스트림 채널 열기
                Http2StreamChannelBootstrap sb = new Http2StreamChannelBootstrap(parent);
                sb.handler(new ChannelInitializer<Http2StreamChannel>() {
                    @Override
                    protected void initChannel(Http2StreamChannel ch) {
                        // 스트림 전용 핸들러에 콜백 주입
                        ch.pipeline().addLast(new ResponseHandler((headers, body) -> {
                            System.out.println("\n[Stream " + idx + "] Response:");
                            System.out.println("status : " + headers.status());
//                            headers.forEach(h -> System.out.println(h.getKey() + ": " + h.getValue()));
                            System.out.println("Body: " + (body.isEmpty() ? "[없음]" : body));
                        }));
                    }
                });
                Http2StreamChannel stream = (Http2StreamChannel) sb.open().sync().getNow();

                // 요청 헤더/바디 구성
                Http2Headers h2 = new DefaultHttp2Headers()
                    .method(METHOD).path(paths[i%2-1])
                    .scheme("http").authority(HOST + ":" + PORT)
                    .add("content-type", "application/json");

                stream.write(new DefaultHttp2HeadersFrame(h2, false));
                stream.writeAndFlush(new DefaultHttp2DataFrame(
                    Unpooled.copiedBuffer(bodys[i%2-1], CharsetUtil.UTF_8), true));

                System.out.println("[Stream " + idx + "] Request sent.");
            }

            // (원한다면 적절히 종료 로직 추가)
             Thread.sleep(3000); parent.close().sync();
        } finally {
            // 나중에 반드시 종료
             group.shutdownGracefully();
        }
    }

    public interface ResponseCallback {
        /**
         * @param headers  :status 등 응답 헤더 전체
         * @param body     바디 문자열 (완전한 응답 본문)
         */
        void onResponse(Http2Headers headers, String body);

        /**
         * 예외 발생 시 호출
         */
        default void onError(Throwable cause) {
            cause.printStackTrace();
        }
    }

    private static class ResponseHandler extends ChannelInboundHandlerAdapter {
        private final ResponseCallback callback;
        private final StringBuilder bodyBuf = new StringBuilder();
        private Http2Headers responseHeaders;

        public ResponseHandler(ResponseCallback callback) {
            this.callback = callback;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                if (msg instanceof Http2HeadersFrame) {
                    Http2HeadersFrame hf = (Http2HeadersFrame) msg;
                    if (responseHeaders == null) {
                        responseHeaders = hf.headers();
                    } else {
                        responseHeaders.add(hf.headers());
                    }
                    if (hf.isEndStream()) {
                        callback.onResponse(responseHeaders, "");
                    }
                }
                else if (msg instanceof Http2DataFrame) {
                    Http2DataFrame df = (Http2DataFrame) msg;
                    bodyBuf.append(df.content().toString(CharsetUtil.UTF_8));
                    if (df.isEndStream()) {
                        callback.onResponse(responseHeaders, bodyBuf.toString());
                    }
                }
            } catch (Throwable t) {
                callback.onError(t);
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }
    }
}
