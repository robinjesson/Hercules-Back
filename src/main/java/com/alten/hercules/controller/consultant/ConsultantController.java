package com.alten.hercules.controller.consultant;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import javax.validation.Valid;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.alten.hercules.controller.consultant.http.request.AddConsultantRequest;
import com.alten.hercules.controller.consultant.http.request.UpdateConsultantRequest;
import com.alten.hercules.dal.ConsultantDAL;
import com.alten.hercules.model.consultant.Consultant;
import com.alten.hercules.model.diploma.Diploma;
import com.alten.hercules.model.exception.RessourceNotFoundException;
import com.alten.hercules.model.user.Manager;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/hercules/consultants")
public class ConsultantController {

	@Autowired
	private ConsultantDAL dal;

	@GetMapping("")
	public ResponseEntity<Object> getAll(@RequestParam boolean enabled) {
		return enabled ?
				ResponseEntity.ok(dal.findAllEnabled()) :
				ResponseEntity.ok(dal.findAll());
	}

	@GetMapping("/{id}")
	public ResponseEntity<Object> getById(@PathVariable Long id) {

		if (!this.dal.findById(id).isPresent())
			return ResponseEntity.notFound().build();
		return ResponseEntity.ok(this.dal.findById(id).get());
	}

	@PostMapping("")
	public ResponseEntity<Object> addConsultant(@Valid @RequestBody AddConsultantRequest request) {

		Optional<Consultant> optConsultant = dal.findByEmail(request.getEmail());
		if (optConsultant.isPresent())
			return ResponseEntity.accepted().body(optConsultant.get().getId());

		if (dal.userExistsByEmail(request.getEmail()))
			return ResponseEntity.status(HttpStatus.CONFLICT).build();

		try {
			Manager manager = dal.findManagerById(request.getManager())
					.orElseThrow(() -> new RessourceNotFoundException());

			Set<Diploma> diplomas = new HashSet<Diploma>();
			if (request.getDiplomas() != null)
				for (Long diploma : request.getDiplomas())
					diplomas.add(dal.findDiplomaById(diploma).orElseThrow(() -> new RessourceNotFoundException()));

			Consultant consultant = new Consultant(request.getEmail(), request.getFirstname(), request.getLastname(),
					request.getExperience(), manager, diplomas);
			dal.save(consultant);
			return ResponseEntity.created(null).body(consultant.getId());
		} catch (RessourceNotFoundException e) {
			return ResponseEntity.notFound().build();
		}
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<?> deleteConsultant(@PathVariable Long id) {
		Optional<Consultant> optConsultant = dal.findById(id);
		if (!optConsultant.isPresent())
			return ResponseEntity.notFound().build();

		Consultant consultant = optConsultant.get();
		if (!consultant.getMissions().isEmpty())
			return new ResponseEntity<>(HttpStatus.CONFLICT);

		dal.delete(consultant);
		return ResponseEntity.ok().build();
	}

	@PutMapping
	public ResponseEntity<?> updateConsultant(@Valid @RequestBody UpdateConsultantRequest req) {
		Optional<Consultant> optConsultant = dal.findById(req.getId());
		if (!optConsultant.isPresent())
			return ResponseEntity.notFound().build();
		Consultant consultant = optConsultant.get();

		if (req.getEmail() != null) {
			if (dal.userExistsByEmail(req.getEmail()))
				return ResponseEntity.status(HttpStatus.CONFLICT).build();
			consultant.setEmail(req.getEmail());
		}

		if (req.getExperience() != null)
			consultant.setExperience(req.getExperience());

		if (req.getFirstname() != null) {
			consultant.setFirstname(req.getFirstname());
		}

		if (req.getLastname() != null)
			consultant.setLastname(req.getLastname());

		if (req.getReleaseDate() != null)
			consultant.setReleaseDate(req.getReleaseDate());

		if (req.getManager() != null) {
			Optional<Manager> optManager = dal.findManagerById(req.getManager());
			if (!optManager.isPresent())
				return ResponseEntity.notFound().build();
			Manager m = optManager.get();
			consultant.setManager(m);
		}

		this.dal.save(consultant);

		return ResponseEntity.ok().build();
	}
}