package com.alten.hercules.controller.mission;

import java.util.Optional;

import javax.validation.Valid;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.alten.hercules.dal.MissionDAL;
import com.alten.hercules.model.consultant.Consultant;
import com.alten.hercules.model.customer.Customer;
import com.alten.hercules.model.mission.Mission;
import com.alten.hercules.model.mission.MissionSheet;
import com.alten.hercules.model.mission.request.AddMissionRequest;

@RestController
@CrossOrigin(origins="*")
@RequestMapping("/hercules/missions")
public class MissionController {

	@Autowired private MissionDAL dal;
	
	@GetMapping("/{id}")
	public ResponseEntity<?> finById(@PathVariable Long id) {
		Optional<Mission> optMission = dal.findById(id);
		if (optMission.isEmpty())
			return ResponseEntity.notFound().build();
		
		return ResponseEntity.ok(optMission.get());
	}
	
	@PreAuthorize("hasAuthority('MANAGER')")
	@PostMapping
	public ResponseEntity<?> addMission(@Valid @RequestBody AddMissionRequest req) {
		Optional<Consultant> optConsultant = dal.findConsultantById(req.getConsultant());
		if (optConsultant.isEmpty())
			ResponseEntity.status(HttpStatus.NOT_FOUND).body("Consultant");
		
		Optional<Customer> optCustomer = dal.findCustomerById(req.getCustomer());
		if (optCustomer.isEmpty())
			ResponseEntity.status(HttpStatus.NOT_FOUND).body("Customer");

		Mission mission = new Mission(optConsultant.get(), optCustomer.get());
		MissionSheet v0 = new MissionSheet(mission);
		dal.save(mission);
		dal.saveSheet(v0);
		mission.addVersion(v0);
		dal.save(mission);

		return ResponseEntity.status(HttpStatus.CREATED).body(mission.getId());
	}
}
