package server2;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.*;
import io.netty.util.CharsetUtil;

import java.nio.charset.StandardCharsets;

public class StreamHandler extends ChannelInboundHandlerAdapter {

    private Http2Headers headers;
    private final ByteBuf body = Unpooled.buffer();

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof Http2HeadersFrame) {
            Http2HeadersFrame headersFrame = (Http2HeadersFrame) msg;
            this.headers = headersFrame.headers();

            if (headersFrame.isEndStream()) {
                // 바디 없이 끝나는 경우 (GET 등)
                handleRequest(ctx, headers, Unpooled.EMPTY_BUFFER);
            }

        } else if (msg instanceof Http2DataFrame) {
            Http2DataFrame dataFrame = (Http2DataFrame) msg;
            body.writeBytes(dataFrame.content());

            if (dataFrame.isEndStream()) {
                // 바디 전체 수신 완료
                handleRequest(ctx, headers, body.copy());
                body.clear(); // or release if using ref-counted buffer
            }

            dataFrame.release(); // ref-counted 객체 해제
        } else {
            ctx.fireChannelRead(msg); // 기타 프레임 전달
        }
    }

    private void handleRequest(ChannelHandlerContext ctx, Http2Headers headers, ByteBuf fullBody) {
        // path, method 등 headers에서 추출
        String path = headers.path().toString();
        String method = headers.method().toString();
        String bodyString = fullBody.toString(CharsetUtil.UTF_8);

        // 여기에 로직 처리
        System.out.printf("Got %s %s\nBody: %s\n", method, path, bodyString);

        // 응답 예시
        String responseString = bodyString;
//        String responseString = "ERROR";
        ByteBuf responseContent = ctx.alloc()
            .buffer().writeBytes(responseString.getBytes(StandardCharsets.UTF_8));

        Http2Headers responseHeaders = new DefaultHttp2Headers()
                .status("200")
                .set("content-type", "text/plain");

        ctx.write(new DefaultHttp2HeadersFrame(responseHeaders));
        ctx.writeAndFlush(new DefaultHttp2DataFrame(responseContent, true));
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
//        body.release(); // clean up
        super.channelInactive(ctx);
    }
}