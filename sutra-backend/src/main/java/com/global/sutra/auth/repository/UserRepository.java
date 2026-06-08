package com.global.sutra.auth.repository;

import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.data.mongodb.repository.MongoRepository;
import com.global.sutra.auth.model.User;

@Repository
public interface UserRepository  extends MongoRepository<User, String>{
	Optional<User> findByEmail(String email);
	boolean existsByEmail(String email);
}
