package com.global.sutra.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RegisterRequest {
	@NotBlank(message = "Email is Required")
	@Email(message = "Invalid Email Formate")
	private String email;
	
	@NotBlank(message = "First name is required")
    private String firstName;

    @NotBlank(message = "Last name is required")
    private String lastName;
	
	@NotBlank(message = "Password is Required")
	@Size(min = 8, message = "Password must be at least 8 characters")
	private String password;
}
