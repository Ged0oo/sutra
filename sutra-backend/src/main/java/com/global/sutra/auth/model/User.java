package com.global.sutra.auth.model;

import java.time.Instant;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "users")
public class User {
	@Id
	private String id;
	private Role role;
	
	private String email;
	private String passwordHash;
	private String refreshTokenHash;
	
	private boolean isActive;
	private boolean isEmailVerified;
		
	@LastModifiedDate
	private Instant updatedAt;
	
	@CreatedDate
	private Instant createdAt;
	
	private Instant lastLoginAt;
}
