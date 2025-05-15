package server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.codec.http2.CleartextHttp2ServerUpgradeHandler;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2ServerUpgradeCodec;
import io.netty.util.AsciiString;

public class Http2CleartextServer {
    private final int port;
    public Http2CleartextServer(int port) {
        this.port = port;
    }

    public void start() throws InterruptedException {
        // 스레드 그룹 설정 (boss는 접속 수락, worker는 소켓 I/O 처리)
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline p = ch.pipeline();

                        // 1. HTTP/1.1 서버 코덱 (요청 디코딩/응답 인코딩)
                        HttpServerCodec httpServerCodec = new HttpServerCodec();

                        // 2. HTTP/2 핸들러 객체 생성 (아래에서 구현)
                        Http2ServerHandler http2Handler = new Http2ServerHandler();

                        // 3. 업그레이드 코덱 팩토리: HTTP/1.1 "Upgrade: h2c" 헤더 처리
                        HttpServerUpgradeHandler.UpgradeCodecFactory upgradeFactory = protocol -> {
                            // HTTP/2 업그레이드 프로토콜 식별
                            if (AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME, protocol)) {
                                // HTTP/2로 업그레이드 시 사용할 Codec 지정
                                return new Http2ServerUpgradeCodec(http2Handler);
                            }
                            return null;
                        };
                        HttpServerUpgradeHandler upgradeHandler = new HttpServerUpgradeHandler(httpServerCodec, upgradeFactory);

                        // 4. Cleartext HTTP/2 업그레이드 핸들러 (h2c 프라이어놀리지 또는 업그레이드 모두 지원)
                        CleartextHttp2ServerUpgradeHandler cleartextH2cHandler =
                            new CleartextHttp2ServerUpgradeHandler(httpServerCodec, upgradeHandler, http2Handler);

                        // 5. 파이프라인에 추가: 첫 핸들러로 cleartextH2cHandler 추가
                        p.addLast(cleartextH2cHandler);
                        // (참고: CleartextHttp2ServerUpgradeHandler는 HTTP/2 프레임 프리페이스(prior knowledge)를 감지하거나
                        // "Upgrade: h2c" 헤더를 통해 HTTP/1.1을 HTTP/2로 업그레이드합니다:contentReference[oaicite:0]{index=0}:contentReference[oaicite:1]{index=1}.
                        // 업그레이드가 완료되면 내부적으로 HTTP/1 코덱은 제거되고 HTTP/2 프레임 핸들러가 동작합니다.)
                    }
                });

            // 6. 서버 채널 바인드 및 시작
            Channel ch = b.bind(port).sync().channel();
            System.out.println("** HTTP/2 Cleartext server started on port " + port + " **");
            ch.closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    // 메인 메서드: 포트 지정하여 서버 시작
    public static void main(String[] args) throws InterruptedException {
        int port = (args.length > 0) ? Integer.parseInt(args[0]) : 8080;
        new Http2CleartextServer(port).start();
    }
}
