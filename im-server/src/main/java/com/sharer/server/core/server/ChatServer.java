package com.sharer.server.core.server;

import com.google.protobuf.MessageLite;
import com.google.protobuf.MessageLiteOrBuilder;
import com.sharer.server.core.cocurrent.FutureTaskScheduler;
import com.sharer.server.core.distributed.ImWorker;
import com.sharer.server.core.distributed.WorkerRouter;
import com.sharer.server.core.encoder.WebSocketProtobufDecoder;
import com.sharer.server.core.encoder.WebSocketProtobufEncoder;
import com.sharer.server.core.handler.*;
import com.sharer.server.core.proto.RequestProto;
import com.sharer.server.core.utils.JsonUtils;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.handler.codec.protobuf.ProtobufDecoder;
import io.netty.handler.codec.protobuf.ProtobufEncoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateHandler;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.List;

import static io.netty.buffer.Unpooled.wrappedBuffer;

public class ChatServer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatServer.class);
    // socket
    // ??????nio????????????????????????????????????
    private EventLoopGroup boss;
    private EventLoopGroup worker;
    // ???????????????
    private ServerBootstrap bootstrap;

    // webscoket
    private EventLoopGroup webBoss;
    private EventLoopGroup webWorker;
    private ServerBootstrap webBootstrap;

    private String ip;

    private Integer port;

    private Integer webPort;


    @Autowired
    private LoginRequestHandler loginRequestHandler;

    @Autowired
    private MessageRequestHandler messageRequestHandler;

    @Autowired
    private RemoteNotificationHandler remoteNotificationHandler;

    @Autowired
    private ServerExceptionHandler serverExceptionHandler;

    /**
     * ???????????????(???)
     */
    public static final int READ_IDLE_TIME = 150;

    /**
     * ??????????????????(???)
     */
    public static final int WRITE_IDLE_TIME = 120;

    /**
     * ???????????? ?????????30???
     */
    public static final int PONG_TIME_OUT = 10;

    private ChatServer(ChatServer.Builder builder) {
        this.ip = builder.ip;
        this.port = builder.appPort;
        this.webPort = builder.webPort;
    }

    /**
     * ????????????
     */
    public void bind() {
        if (this.port != null) {
            this.bindPort();
        }
        if (this.webPort != null) {
            this.bindWebPort();
        }

        ImWorker.getInst().setLocalNode(ip, port, webPort);

        FutureTaskScheduler.add(() -> {
            /**
             * ????????????
             */
            ImWorker.getInst().init();
            /**
             * ?????????????????????
             */
            WorkerRouter.getInst().init();
        });


    }

    /**
     * ???????????????????????????
     *
     * @param pipeline
     */
    private void pipelineSet(ChannelPipeline pipeline) {
        pipeline.addLast(new LoggingHandler(LogLevel.INFO));
        pipeline.addLast(new IdleStateHandler(READ_IDLE_TIME, WRITE_IDLE_TIME, 0));
        pipeline.addLast("heartBeat", new HeartBeatServerHandler());
        pipeline.addLast("loginRequestHandler", loginRequestHandler);
        pipeline.addLast("messageRequestHandler", messageRequestHandler);
        pipeline.addLast("remoteNotificationHandler", remoteNotificationHandler);
        pipeline.addLast("serverException", serverExceptionHandler);
    }

    /**
     * ??????socket????????????
     */
    public void bindPort() {
        //?????????????????????
        boss = new NioEventLoopGroup(1);
        //?????????????????????
        worker = new NioEventLoopGroup();
        // ?????????
        bootstrap = new ServerBootstrap();
        //1 ??????reactor ??????
        bootstrap.group(boss, worker);
        //2 ??????nio?????????channel
        bootstrap.channel(NioServerSocketChannel.class);
        //3 ????????????
        bootstrap.localAddress(new InetSocketAddress(ip, port));
        //4 ??????????????????
        bootstrap.option(ChannelOption.SO_KEEPALIVE, true);
        //5 ?????? ??????????????????
        bootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) throws Exception {
                ChannelPipeline pipeline = ch.pipeline();

                pipeline.addLast(new ProtobufVarint32FrameDecoder());
                pipeline.addLast(new ProtobufDecoder(RequestProto.Request.getDefaultInstance()));
                pipeline.addLast(new ProtobufVarint32LengthFieldPrepender());
                pipeline.addLast(new ProtobufEncoder());

                pipelineSet(pipeline);
            }
        });

        ChannelFuture channelFuture = bootstrap.bind().syncUninterruptibly();
        channelFuture.channel().newSucceededFuture().addListener(future -> {
            String logBanner = "\n\n" +
                    "* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *\n" +
                    "*                                                                                   *\n" +
                    "*                                                                                   *\n" +
                    "*                   App Socket Server started on port {}.                         *\n" +
                    "*                                                                                   *\n" +
                    "*                                                                                   *\n" +
                    "* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *\n";
            LOGGER.info(logBanner, port);
        });
        channelFuture.channel().closeFuture().addListener(future -> this.destroy(boss, worker));
    }

    /**
     * ??????webscoket????????????
     */
    public void bindWebPort() {
        //?????????????????????
        webBoss = new NioEventLoopGroup(1);
        //?????????????????????
        webWorker = new NioEventLoopGroup();
        webBootstrap = new ServerBootstrap();
        //1 ??????reactor ??????
        webBootstrap.group(webBoss, webWorker);
        //2 ??????nio?????????channel
        webBootstrap.channel(NioServerSocketChannel.class);
        //3 ????????????
        webBootstrap.localAddress(new InetSocketAddress(ip, webPort));
        //4 ??????????????????
        webBootstrap.option(ChannelOption.SO_KEEPALIVE, true);
        //5 ?????? ??????????????????
        webBootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) throws Exception {
                ChannelPipeline pipeline = ch.pipeline();
                // webscoket????????????
                pipeline.addLast("logging-handler", new LoggingHandler(LogLevel.INFO));
                pipeline.addLast("http-codec", new HttpServerCodec());
                pipeline.addLast("aggregator", new HttpObjectAggregator(65536));
                pipeline.addLast("http-chunked", new ChunkedWriteHandler());
                pipeline.addLast(new WebSocketServerCompressionHandler());
                // ?????????????????????
                pipeline.addLast(new WebSocketServerProtocolHandler("/chat", null, true, 1024 * 10));
                // ???????????????
                pipeline.addLast(new MessageToMessageDecoder<WebSocketFrame>() {
                    @Override
                    protected void decode(ChannelHandlerContext ctx, WebSocketFrame frame, List<Object> objs) throws Exception {
                        ByteBuf buf = frame.content();
                        objs.add(buf);
                        buf.retain();
                    }
                });
                //???????????? ???protoBuf????????????WebSocketFrame
                pipeline.addLast(new ProtobufEncoder() {
                    @Override
                    protected void encode(ChannelHandlerContext ctx, MessageLiteOrBuilder msg, List<Object> out) throws Exception {
                        RequestProto.Request request = (RequestProto.Request) msg;
                        WebSocketFrame frame = new BinaryWebSocketFrame(Unpooled.wrappedBuffer(request.toByteArray()));
                        out.add(frame);
                    }
                });
                pipeline.addLast(new ProtobufDecoder(RequestProto.Request.getDefaultInstance()));
                pipeline.addLast(new ProtobufVarint32LengthFieldPrepender());
                //pipeline.addLast(new LoggingHandler(LogLevel.INFO));
                //pipeline.addLast(new IdleStateHandler(READ_IDLE_TIME, WRITE_IDLE_TIME, 0));
                //pipeline.addLast("loginRequestHandler", loginRequestHandler);

                pipelineSet(pipeline);
            }
        });

        ChannelFuture channelFuture = webBootstrap.bind().syncUninterruptibly();
        channelFuture.channel().newSucceededFuture().addListener(future -> {
            String logBanner = "\n\n" +
                    "* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *\n" +
                    "*                                                                                   *\n" +
                    "*                                                                                   *\n" +
                    "*                   webScoket Socket Server started on port {}.                   *\n" +
                    "*                                                                                   *\n" +
                    "*                                                                                   *\n" +
                    "* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *\n";
            LOGGER.info(logBanner, webPort);
        });
        channelFuture.channel().closeFuture().addListener(future -> this.destroy(webBoss, webWorker));
    }

    // ??????netty
    public void destroy() {
        this.destroy(this.boss, this.worker);
        this.destroy(this.webBoss, this.webWorker);
    }

    public void destroy(EventLoopGroup bossGroup, EventLoopGroup workerGroup) {
        if (bossGroup != null && !bossGroup.isShuttingDown() && !bossGroup.isShutdown()) {
            try {
                bossGroup.shutdownGracefully();
            } catch (Exception var5) {
            }
        }
        if (workerGroup != null && !workerGroup.isShuttingDown() && !workerGroup.isShutdown()) {
            try {
                workerGroup.shutdownGracefully();
            } catch (Exception var4) {
            }
        }

    }


    public static class Builder {
        private String ip;
        private Integer appPort;
        private Integer webPort;

        public Builder() {
        }

        public ChatServer.Builder setAppPort(Integer appPort) {
            this.appPort = appPort;
            return this;
        }

        public ChatServer.Builder setWebsocketPort(Integer port) {
            this.webPort = port;
            return this;
        }

        public ChatServer.Builder setIp(String ip) {
            this.ip = ip;
            return this;
        }

//        public ChatServer.Builder setOuterRequestHandler(CIMRequestHandler outerRequestHandler) {
//            this.outerRequestHandler = outerRequestHandler;
//            return this;
//        }

        public ChatServer build() {
            return new ChatServer(this);
        }
    }

}
