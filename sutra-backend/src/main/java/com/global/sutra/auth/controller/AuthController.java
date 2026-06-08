package com.global.sutra.auth.controller;

import com.global.sutra.auth.dto.AuthenticationRequest;
import com.global.sutra.auth.dto.AuthenticationResponse;
import com.global.sutra.auth.dto.RegisterRequest;
import com.global.sutra.auth.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
	private final AuthService authService;
	
	@PostMapping("/register")
	public ResponseEntity<AuthenticationResponse> register(@Valid @RequestBody RegisterRequest request){
		return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
	}
	
	@PostMapping("/authenticate")
    public ResponseEntity<AuthenticationResponse> authenticate(@Valid @RequestBody AuthenticationRequest request) {
        return ResponseEntity.ok(authService.authenticate(request));
    }
}
