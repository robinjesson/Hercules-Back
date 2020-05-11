package com.alten.hercules.controller.mission;

import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.validation.Valid;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.alten.hercules.controller.http.request.UpdateEntityRequest;
import com.alten.hercules.controller.mission.http.request.AddMissionRequest;
import com.alten.hercules.controller.mission.http.response.BasicMissionDetailsResponse;
import com.alten.hercules.controller.mission.http.response.BasicMissionResponse;
import com.alten.hercules.controller.mission.http.response.MissionDetailsResponse;
import com.alten.hercules.dal.MissionDAL;
import com.alten.hercules.model.consultant.Consultant;
import com.alten.hercules.model.customer.Customer;
import com.alten.hercules.model.exception.AlreadyExistingVersionException;
import com.alten.hercules.model.exception.InvalidFieldnameException;
import com.alten.hercules.model.exception.InvalidValueException;
import com.alten.hercules.model.exception.ResponseEntityException;
import com.alten.hercules.model.exception.RessourceNotFoundException;
import com.alten.hercules.model.exception.UnvalidatedMissionSheetException;
import com.alten.hercules.model.mission.EContractType;
import com.alten.hercules.model.mission.EMissionFieldname;
import com.alten.hercules.model.mission.ESheetStatus;
import com.alten.hercules.model.mission.Mission;
import com.alten.hercules.model.mission.MissionSheet;

@RestController
@CrossOrigin(origins="*")
@RequestMapping("/hercules/missions")
public class MissionController {

	@Autowired private MissionDAL dal;
	
	@GetMapping("/{id}")
	public ResponseEntity<?> getMission(@PathVariable Long id) {
		return getMissionDetails(id, true);
	}
	
	@GetMapping("/from-token")
	public ResponseEntity<?> getMissionFromToken() {
		Long id = (Long)(SecurityContextHolder.getContext().getAuthentication().getPrincipal());
		return getMissionDetails(id, false);
	}
	
	private ResponseEntity<?> getMissionDetails(Long id, boolean complete) {
		try {
			Mission mission = dal.findById(id).orElseThrow(() -> new RessourceNotFoundException("Mission"));
			return ResponseEntity.ok(complete ? 
					new MissionDetailsResponse(mission) :
					new BasicMissionDetailsResponse(mission));
		} catch (RessourceNotFoundException e) {
			return e.buildResponse();
		}
	}
	
	@GetMapping("")
	public ResponseEntity<?> getAll(@RequestParam boolean details) {
		return ResponseEntity.ok(details ? getAllExtended() : getAllReduced());
	}
	
	private List<BasicMissionResponse> getAllReduced() {
		return dal.findAll().stream()
				.map((mission) -> new BasicMissionResponse(mission))
				.collect(Collectors.toList());
	}
	
	private List<MissionDetailsResponse> getAllExtended() {
		return dal.findAll().stream()
			.map((mission) -> new MissionDetailsResponse(mission))
			.collect(Collectors.toList());
	}

	@PreAuthorize("hasAuthority('MANAGER')")
	@PostMapping
	public ResponseEntity<?> addMission(@Valid @RequestBody AddMissionRequest req) {
		try {
			Optional<Consultant> optConsultant = dal.findConsultantById(req.getConsultant());
			if (optConsultant.isEmpty())
				throw new RessourceNotFoundException("Consultant");
			Optional<Customer> optCustomer = dal.findCustomerById(req.getCustomer());
			if (optCustomer.isEmpty())
				throw new RessourceNotFoundException("Customer");
			Mission mission = new Mission(optConsultant.get(), optCustomer.get());
			MissionSheet v0 = new MissionSheet(mission);
			dal.save(mission);
			dal.saveSheet(v0);
			return ResponseEntity.status(HttpStatus.CREATED).body(mission.getId());
		} catch (RessourceNotFoundException e) {
			return e.buildResponse();
		}
	}
	
	@PreAuthorize("hasAuthority('MANAGER')")
	@GetMapping("/new-version/{id}")
	public ResponseEntity<?> newVersion(@PathVariable Long id) {
		try {
			Mission mission = dal.findById(id)
					.orElseThrow(() -> new RessourceNotFoundException("Mission"));
			
			if (!mission.getSheetStatus().equals(ESheetStatus.VALIDATED))
				throw new UnvalidatedMissionSheetException();
			
			MissionSheet mostRecentVersion = dal.findMostRecentVersion(id)
					.orElseThrow(() -> new RessourceNotFoundException("Sheet"));
			
			if (isToday(mostRecentVersion.getVersionDate()))
				throw new AlreadyExistingVersionException();
			
			dal.saveSheet(new MissionSheet(mostRecentVersion, new Date()));
			dal.changeMissionSecret(mission);
			return ResponseEntity.status(HttpStatus.CREATED).build();
		} catch (ResponseEntityException e) {
			return e.buildResponse();
		}
	}
	
	@PreAuthorize("hasAuthority('MANAGER')")
	@PutMapping
	public ResponseEntity<?> putMission(@Valid @RequestBody UpdateEntityRequest req) {
		return updateMission(req.getId(), req.getFieldName(), req.getValue());
	}
	
	@PutMapping("/from-token")
	public ResponseEntity<?> putMissionFromToken(@RequestBody UpdateEntityRequest req) {
		Long id = (Long)(SecurityContextHolder.getContext().getAuthentication().getPrincipal());
		if (req.getFieldName() == null)
			return ResponseEntity
					.status(HttpStatus.BAD_REQUEST)
					.build();
		return updateMission(id, req.getFieldName(), req.getValue());
	}
	
	private ResponseEntity<?> updateMission(Long id, String key, Object value) {
		try {
			MissionSheet mostRecentVersion = dal.findMostRecentVersion(id)
					.orElseThrow(() -> new RessourceNotFoundException("Sheet"));
			
			EMissionFieldname fieldname;
			try { fieldname = EMissionFieldname.valueOf(key); }
			catch (IllegalArgumentException e) { throw new InvalidFieldnameException(); }
			switch(fieldname) {
				case city :
					mostRecentVersion.setCity((String)value);
					break;
				case comment :
					mostRecentVersion.setComment((String)value);
					break;
				case consultantRole :
					mostRecentVersion.setConsultantRole((String)value);
					break;
				case consultantStartXp :
					mostRecentVersion.setConsultantStartXp((Integer)value);
					break;
				case contractType :
					try { 
						EContractType contractType = EContractType.valueOf((String)value);
						mostRecentVersion.setContractType(contractType);
					} catch (IllegalArgumentException e) { throw new InvalidValueException(); }
					break;
				case country :
					mostRecentVersion.setCountry((String)value);
					break;
				case description :
					mostRecentVersion.setDescription((String)value);
					break;
				case teamSize :
					mostRecentVersion.setTeamSize((Integer)value);
					break;
				case title :
					mostRecentVersion.setTitle((String)value);
					break;
				default: throw new InvalidFieldnameException();
			}
			
			Mission mission = dal.findById(id).get();
			if (mission.getSheetStatus().equals(ESheetStatus.ON_WAITING)) {
				mission.setSheetStatus(ESheetStatus.ON_GOING);
				dal.save(mission);
			}
			
			dal.saveSheet(mostRecentVersion);
			return ResponseEntity.ok().build();
		} catch (ResponseEntityException e) {
			return e.buildResponse();
		} catch (ClassCastException | NullPointerException e) {
			return new InvalidValueException().buildResponse();
		}
	}
	
	private boolean isToday(Date date) {
		Calendar todayCalendar = Calendar.getInstance();
		Calendar lastVersionCalendar = Calendar.getInstance();
		lastVersionCalendar.setTime(date);
		
		return todayCalendar.get(Calendar.DAY_OF_YEAR) == lastVersionCalendar.get(Calendar.DAY_OF_YEAR) 
				&& todayCalendar.get(Calendar.YEAR) == lastVersionCalendar.get(Calendar.YEAR);
	}
}
