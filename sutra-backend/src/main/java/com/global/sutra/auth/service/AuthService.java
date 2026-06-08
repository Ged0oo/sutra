package com.global.sutra.auth.service;

import com.global.sutra.auth.dto.AuthenticationRequest;
import com.global.sutra.auth.dto.AuthenticationResponse;
import com.global.sutra.auth.dto.RegisterRequest;
import com.global.sutra.auth.model.Role;
import com.global.sutra.auth.model.User;
import com.global.sutra.auth.repository.UserRepository;
import com.global.sutra.auth.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;

import javax.management.RuntimeErrorException;

@Service
@RequiredArgsConstructor
public class AuthService {
	private final JwtUtil jwtUtil;
	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final AuthenticationManager authenticationManager;
	
	public AuthenticationResponse register (RegisterRequest request) {
		if(userRepository.existsByEmail(request.getEmail())){
			throw new RuntimeException("Email is already in use");
		}
		
		User user = User.builder()
						.firstName(request.getFirstName())
						.lastName(request.getLastName())
						.email(request.getEmail())
						.passwordHash(passwordEncoder.encode(request.getPassword()))
						.role(Role.CUSTOMER)
						.isActive(true)
						.isEmailVerified(false)
						.createdAt(Instant.now())
						.updatedAt(Instant.now())
						.build();
		
		userRepository.save(user);
		
		String jwtToken = jwtUtil.generateAccessToken(user);
		String refreshToken = jwtUtil.generateRefreshToken(user);
		
		return AuthenticationResponse.builder()
								     .accessToken(jwtToken)
								     .refreshToken(refreshToken)
								     .email(user.getEmail())
								     .role(user.getRole().name())
								     .build();
	}
	
	public AuthenticationResponse authenticate(AuthenticationRequest request) {
		authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword()));
		
		User user = userRepository.findByEmail(request.getEmail()).orElseThrow(() -> new RuntimeException("User not found"));
		
		user.setLastLoginAt(Instant.now());
		userRepository.save(user);
		
		String jwtToken = jwtUtil.generateAccessToken(user);
		String refreshToken = jwtUtil.generateRefreshToken(user);
		
		return AuthenticationResponse.builder()
								     .accessToken(jwtToken)
								     .refreshToken(refreshToken)
								     .email(user.getEmail())
								     .role(user.getRole().name())
								     .build();
	}
}
