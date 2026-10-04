package com.chatapp.config;

import com.chatapp.repository.UserRepository;
import com.chatapp.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.Collections;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Registers the WebSocket handshake endpoint. Clients connect here to upgrade HTTP → WebSocket.
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // In-memory broker handles delivery to subscribers on /queue/** destinations
        registry.enableSimpleBroker("/queue");
        // Messages sent to /app/** are routed to @MessageMapping controller methods
        registry.setApplicationDestinationPrefixes("/app");
        // /user/** prefix enables per-user delivery (convertAndSendToUser targets this)
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // Intercepts every inbound STOMP frame before it reaches the controller
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                // Unwrap the STOMP headers from the raw message
                StompHeaderAccessor accessor =
                        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                // Only validate JWT on the CONNECT frame — this is the STOMP-level auth handshake.
                // /ws is permitAll at HTTP level (browser can't send custom headers during WS upgrade),
                // so auth is enforced here instead, on the first STOMP frame after the connection opens.
                if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
                    String authHeader = accessor.getFirstNativeHeader("Authorization"); // read JWT from STOMP CONNECT headers (set by api.js connectHeaders)
                    if (authHeader != null && authHeader.startsWith("Bearer ")) {
                        String token = authHeader.substring(7); // strip "Bearer " prefix
                        if (jwtUtil.validateToken(token)) {
                            String username = jwtUtil.extractUsername(token);
                            var user = userRepository.findByUsername(username); // load full User entity from DB
                            if (user.isPresent()) {
                                // Store the authenticated User on the STOMP session.
                                // This is what Principal principal resolves to in @MessageMapping methods.
                                // getName() is overridden because User is a plain JPA entity (not UserDetails),
                                // so the default getName() would return toString() — convertAndSendToUser() needs the plain username string.
                                var authToken = new UsernamePasswordAuthenticationToken(
                                        user.get(), null, Collections.emptyList()) {
//                                  convertAndSendToUser("john", "/queue/messages", response) tells Spring to deliver to the subscriber named "john".
//                                  Spring finds that subscriber by scanning all connected STOMP sessions and calling getName() on each session's principal.
//                                  Without the override, getName() returns the Lombok toString():
                                    @Override
                                    public String getName() {
                                        return username;
                                    }
                                };
                                accessor.setUser(authToken); // binds this user to the STOMP session for the lifetime of the WebSocket connection
                            }
                        }
                    }
                }
                return message; // always return the message — returning null would drop it
            }
        });
    }
}
