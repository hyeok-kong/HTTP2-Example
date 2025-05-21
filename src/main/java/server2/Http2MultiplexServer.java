package server2;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2StreamChannel;

public class Http2MultiplexServer {
    public static void main(String[] args) throws Exception {
        int port = 8443;

        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();
                            // HTTP/2 프레임 디코더 및 스트림 multiplexer 핸들러 추가
                            Http2FrameCodecBuilder frameCodecBuilder = Http2FrameCodecBuilder.forServer()
                                    .initialSettings(Http2Settings.defaultSettings())
                                    .validateHeaders(true); // Optional
                            p.addLast(frameCodecBuilder.build());
                            p.addLast(new Http2MultiplexHandler(
                                new ChannelInitializer<Http2StreamChannel>() {
                                    @Override
                                    protected void initChannel(Http2StreamChannel ch) throws Exception {
                                        ch.pipeline().addLast(new StreamHandler());
                                    }
                                }
                            ));
                        }
                    });

            Channel ch = b.bind(port).sync().channel();
            System.out.println("HTTP/2 server started on port " + port);
            ch.closeFuture().sync();
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}
