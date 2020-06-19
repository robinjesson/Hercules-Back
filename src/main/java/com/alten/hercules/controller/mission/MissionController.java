package com.alten.hercules.controller.mission;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

import org.apache.commons.io.FilenameUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
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
import org.springframework.web.multipart.MultipartFile;

import com.alten.hercules.controller.http.request.UpdateEntityRequest;
import com.alten.hercules.controller.mission.http.request.AddMissionRequest;
import com.alten.hercules.controller.mission.http.request.GeneratePDFRequest;
import com.alten.hercules.controller.mission.http.response.RefinedMissionResponse;
import com.alten.hercules.controller.mission.http.response.CompleteMissionResponse;
import com.alten.hercules.dal.MissionDAL;
import com.alten.hercules.model.consultant.Consultant;
import com.alten.hercules.model.customer.Customer;
import com.alten.hercules.model.exception.AlreadyExistingVersionException;
import com.alten.hercules.model.exception.EntityDeletionException;
import com.alten.hercules.model.exception.InvalidFieldnameException;
import com.alten.hercules.model.exception.InvalidValueException;
import com.alten.hercules.model.exception.NotLastVersionException;
import com.alten.hercules.model.exception.ProjectsBoundsException;
import com.alten.hercules.model.exception.ResponseEntityException;
import com.alten.hercules.model.exception.ResourceNotFoundException;
import com.alten.hercules.model.exception.InvalidSheetStatusException;
import com.alten.hercules.model.mission.EContractType;
import com.alten.hercules.model.mission.EMissionFieldname;
import com.alten.hercules.model.mission.ESheetStatus;
import com.alten.hercules.model.mission.Mission;
import com.alten.hercules.model.mission.MissionSheet;
import com.alten.hercules.model.project.EProjectFieldname;
import com.alten.hercules.model.project.Project;
import com.alten.hercules.model.skill.Skill;
import com.alten.hercules.model.user.AppUser;
import com.alten.hercules.model.user.Manager;
import com.alten.hercules.service.PDFGenerator;
import com.alten.hercules.service.StoreImage;

import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

/**
 * Class that manages the requests sent to the API for the missions and projects.
 * @author mfoltz, rjesson, abegue, jbaudot
 *
 */
@RestController
@CrossOrigin(origins="*")
@RequestMapping("/hercules/missions")
public class MissionController {

	@Autowired private MissionDAL dal;
	@Autowired private StoreImage storeImage;
	
	@ApiOperation(
			value = "Get a mission.",
			notes = "Return all informations related to the mission and all his sheets.\n"
					+ "The sheets are sorted from the most recent to the oldest."
	)
	@ApiResponses({
		@ApiResponse(code = 200, message="OK."),
		@ApiResponse(code = 401, message="Invalid authentification token."),
		@ApiResponse(code = 404, message="Mission not found.")
	})
	@GetMapping("/{missionId}")
	public ResponseEntity<?> getMission(
			@ApiParam("Mission's identifier.")
			@PathVariable Long missionId) {
		return getMissionDetails(missionId, true);
	}
	
	@ApiOperation(
			value = "Get a mission (anonymous user).",
			notes = "Return the informations related to the mission and its most recent sheet.\n"
	)
	@ApiResponses({
		@ApiResponse(code = 200, message="OK."),
		@ApiResponse(code = 401, message="Invalid authentification token."),
		@ApiResponse(code = 404, message="Mission not found.")
	})
	@PreAuthorize("hasAuthority('MISSION')")
	@GetMapping("/anonymous")
	public ResponseEntity<?> getMissionFromToken() {
		Long missionId = ((Mission)(SecurityContextHolder.getContext().getAuthentication().getPrincipal())).getId();
		return getMissionDetails(missionId, false);
	}
	
	/**
	 * Function that returns the information of a mission given its state.
	 * @param missionId ID of the mission
	 * @param complete boolean that represents if the mission is completed or not
	 * @return 200 OK<br> 404 The mission is not found
	 */
	private ResponseEntity<?> getMissionDetails(Long missionId, boolean complete) {
		try {
			Mission mission = dal.findById(missionId)
					.orElseThrow(() -> new ResourceNotFoundException(Mission.class));
			return ResponseEntity.ok(complete ? 
					new CompleteMissionResponse(mission, true, true) :
					new RefinedMissionResponse(mission));
		} catch (ResourceNotFoundException e) {
			return e.buildResponse();
		}
	}
	
	@ApiOperation(
			value = "Advanced search within missions.",
			notes = "Return all missions which has the status 'validated' and which match with the set of criteria.\n"
					+ "If the user is a manager, also return the missions linked to his consultants which has other status (and also match with the set of criteria).\n"
					+ "In this second case, the mission are sorted according to their status : first 'on waiting' then 'on doing' then 'validated'.\n"
					+ "Only the most recent sheet (last version) is returned for each mission."
	)
	@ApiResponses({
		@ApiResponse(code = 200, message="OK."),
		@ApiResponse(code = 401, message="Invalid authentification token."),
	})
	@GetMapping("/advancedSearch")
	public ResponseEntity<?> advancedSearch(
			@ApiParam(value="Research criteria associated to their value.")
			@RequestParam Map<String, String> criteria) {
		AppUser user = ((AppUser)(SecurityContextHolder.getContext().getAuthentication().getPrincipal()));
		Optional<Long> optManagerId = Optional.ofNullable(user instanceof Manager ? user.getId() : null);
		
		List<CompleteMissionResponse> bodyComplete = dal.advancedSearchQuery(criteria, optManagerId).stream()
				.map(mission -> new CompleteMissionResponse(mission, false, optManagerId.isPresent()))
				.collect(Collectors.toList());
		
		return ResponseEntity.ok(bodyComplete);
	}
	
	@ApiOperation("Delete a mission.")
	@ApiResponses({
		@ApiResponse(code = 200, message="Mission deleted."),
		@ApiResponse(code = 401, message="Invalid authentification token."),
		@ApiResponse(code = 403, message="User isn't a manager."),
		@ApiResponse(code = 404, message="Mission not found."),
		@ApiResponse(code = 409, message="Mission is versioned.")
	})
	@PreAuthorize("hasAuthority('MANAGER')")
	@DeleteMapping("/{missionId}")
	public ResponseEntity<?> deleteMission(
			@ApiParam("Mission's identifier.")
			@PathVariable Long missionId) {
		try {
			Mission mission = dal.findById(missionId)
				.orElseThrow(() -> new ResourceNotFoundException(Mission.class));
			if (!(mission.getLastVersion().getVersionDate()==null))
				throw new EntityDeletionException("The mission is not on waiting and has a last version date.");
			dal.delete(mission);
			return ResponseEntity
					.ok()
					.build();
		} catch (ResponseEntityException e) {
			return e.buildResponse();
		}
	}
	
	@ApiOperation(
			value = "Get missions.",
			notes = "Return all missions which have the status 'validated'.\n"
					+ "If the user is a manager, also return the missions linked to his consultants which has other status.\n"
					+ "In this second case, the mission are sorted according to their status : first 'on waiting' then 'on doing' then 'validated'.\n"
					+ "Only the most recent sheet (last version) is returned for each mission."
	)
	@ApiResponses({
		@ApiResponse(code = 200, message="OK."),
		@ApiResponse(code = 401, message="Invalid authentification token.")
	})
	@GetMapping
	public ResponseEntity<?> getAll() {
		AppUser user = ((AppUser)(SecurityContextHolder.getContext().getAuthentication().getPrincipal()));
		Optional<Long> optManagerId = Optional.ofNullable(user instanceof Manager ? user.getId() : null);
		List<CompleteMissionResponse> body;
		if (optManagerId.isEmpty()) {
			body = dal.findAllValidated().stream()
					.map(mission -> new CompleteMissionResponse(mission, false, true))
					.collect(Collectors.toList());
		} else {
			body = dal.findAllByManager(optManagerId.get()).stream()
					.map(mission -> new CompleteMissionResponse(mission, false, true))
					.collect(Collectors.toList());
		}
		return ResponseEntity.ok(body);
	}

	@ApiOperation(
			value="Create a mission.",
			notes="Create a mission linked to a consultant and a client."
	)
	@ApiResponses({
		@ApiResponse(code = 201, message="Mission created.", response=Long.class),
		@ApiResponse(code = 401, message="Invalid authentification token."),
		@ApiResponse(code = 403, message="User isn't a manager."),
		@ApiResponse(code = 404, message="Consultant/customer not found.")
	})
	@PreAuthorize("hasAuthority('MANAGER')")
	@PostMapping
	public ResponseEntity<?> addMission(
			@ApiParam(
					"consulant : consulant's identifier;\n"
					+ "customer : customer's identifier."
			)
			@Valid @RequestBody AddMissionRequest request) {
		try {
			Consultant consultant = dal.findConsultantById(request.getConsultant())
					.orElseThrow(() -> new ResourceNotFoundException(Consultant.class));
			Customer customer = dal.findCustomerById(request.getCustomer())
					.orElseThrow(() -> new ResourceNotFoundException(Customer.class));
			Mission mission = dal.save(new Mission(consultant, customer));
			MissionSheet firstVersion = dal.saveSheet(new MissionSheet(mission));
			dal.saveProject(new Project(firstVersion));
			return ResponseEntity.status(HttpStatus.CREATED).body(mission.getId());
		} catch (ResponseEntityException e) {
			return e.buildResponse();
		}
	}
	
	@ApiOperation(
			value="New version",
			notes="Create a new versioned sheet for a mission."
	)
	@ApiResponses({
		@ApiResponse(code = 201, message="New version created."),
		@ApiResponse(code = 401, message="Invalid authentification token."),
		@ApiResponse(code = 403, message="User isn't a manager or mission isn't validated."),
		@ApiResponse(code = 404, message="Mission not found."),
		@ApiResponse(code = 409, message="Today's version already exists.")
	})
	@PreAuthorize("hasAuthority('MANAGER')")
	@GetMapping("/new-version/{missionId}")
	public ResponseEntity<?> newVersion(
			@ApiParam("Mission's identifier.")
			@PathVariable Long missionId) {
		try {
			Mission mission = dal.findById(missionId)
					.orElseThrow(() -> new ResourceNotFoundException(Mission.class));
				if (!mission.isValidated())
					throw new InvalidSheetStatusException();
				MissionSheet lastVersion = dal.findMostRecentVersion(mission.getId()).get();;
				if (isToday(lastVersion.getVersionDate()))
					throw new AlreadyExistingVersionException();
				MissionSheet newVersion = dal.saveSheet(new MissionSheet(lastVersion));
				newVersion.getProjects().forEach(project -> dal.saveProject(project));
				mission.changeSecret();
				mission.setSheetStatus(ESheetStatus.ON_WAITING);
				dal.save(mission);
		} catch (ResponseEntityException e) {
			return e.buildResponse();
		}
		return ResponseEntity.status(HttpStatus.CREATED).build();
	}
	
	@ApiOperation(
			value="Update a mission's field value.",
			notes="Update the value of one of the fields of a mission."
	)
	@ApiResponses({
		@ApiResponse(code = 200, message="Mission's field value updated."),
		@ApiResponse(code = 400, message="Inexistant fieldname or invalid value."),
		@ApiResponse(code = 401, message="Invalid authentification token."),
		@ApiResponse(code = 403, message="User isn't a manager or mission is validated."),
		@ApiResponse(code = 404, message="Mission not found.")
	})
	@PreAuthorize("hasAuthority('MANAGER')")
	@PutMapping
	public ResponseEntity<?> putMission(
			@ApiParam(
					"id : mission's identifier;\n"
					+ "fieldName : field's name to update;\n"
					+ "value : field's new value."
			)
			@Valid @RequestBody UpdateEntityRequest request) {
		return updateMission(request.getId(), request.getFieldName(), request.getValue());
	}
	
	@ApiOperation(
			value="Update a mission's field (anonymous user).",
			notes="Update the value of one of the fields of a mission."
	)
	@ApiResponses({
		@ApiResponse(code = 200, message="Mission's field updated."),
		@ApiResponse(code = 400, message="Inexistant fieldname or invalid value."),
		@ApiResponse(code = 401, message="Invalid access token."),
		@ApiResponse(code = 403, message="Mission is validated."),
		@ApiResponse(code = 404, message="Mission not found.")
	})
	@PreAuthorize("hasAuthority('MISSION')")
	@PutMapping("/anonymous")
	public ResponseEntity<?> putMissionFromToken(
			@ApiParam(
					"id : mission's identifier (unused because contained into the token);\n"
					+ "fieldName : mission's fieldname to update;\n"
					+ "value : field's new value."
			)
			@RequestBody UpdateEntityRequest request) {
		Long id = ((Mission)(SecurityContextHolder.getContext().getAuthentication().getPrincipal())).getId();
		if (request.getFieldName() == null)
			return ResponseEntity
					.status(HttpStatus.BAD_REQUEST)
					.build();
		return updateMission(id, request.getFieldName(), request.getValue());
	}
	
	/**
	 * Function that modifies a specific field of the given mission.
	 * @param id ID of the mission
	 * @param key name of the field to be modified
	 * @param value modified value of the field
	 * @return 200 The mission is modified<br> 400 the field name or the value is bad<br>403 The status doesn't give the right to modify<br>404 The mission is not found
	 */
	private ResponseEntity<?> updateMission(Long id, String key, Object value) {
		try {
			Mission mission = dal.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException(Mission.class));
			MissionSheet mostRecentVersion = dal.findMostRecentVersion(id).get();
			
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
				case sheetStatus :
					try { 
						ESheetStatus status = ESheetStatus.valueOf((String)value);
						if (!status.equals(ESheetStatus.VALIDATED))
							throw new InvalidValueException();
						if (mission.getSheetStatus().equals(ESheetStatus.VALIDATED))
							throw new InvalidSheetStatusException();
						mission.changeSecret();
						mission.setSheetStatus(ESheetStatus.VALIDATED);
						mostRecentVersion.setVersionDate(LocalDate.now());
						dal.save(mission);
					} catch (IllegalArgumentException e) { throw new InvalidValueException(); }
					return ResponseEntity.ok().build(); 
				case teamSize :
					mostRecentVersion.setTeamSize((Integer)value);
					break;
				case title :
					mostRecentVersion.setTitle((String)value);
					break;
				default: throw new InvalidFieldnameException();
			}
			updateSheetStatus(mission);
			dal.saveSheet(mostRecentVersion);
			return ResponseEntity.ok().build();
		} catch (ResponseEntityException e) {
			return e.buildResponse();
		} catch (ClassCastException | NullPointerException e) {
			return new InvalidValueException().buildResponse();
		}
	}
	
	@ApiOperation(
			value="Create project.",
			notes="Create a new empty project for the most recent sheet of a mission."
	)
	@ApiResponses({
		@ApiResponse(code = 201, message="New project created."),
		@ApiResponse(code = 401, message="Invalid authentification token."),
		@ApiResponse(code = 403, message="User isn't a manager or mission has status 'validated' or projects limit reached for the sheet."),
		@ApiResponse(code = 404, message="Mission not found.")
	})
	@PreAuthorize("hasAuthority('MANAGER')")
	@GetMapping("/new-project/{missionId}")
	public ResponseEntity<?> newProject(
			@ApiParam("Mission identifier.")
			@PathVariable Long missionId) {
		try { _newProject(missionId); }
		catch (ResponseEntityException e) {
			return e.buildResponse();
		}
		return ResponseEntity.status(HttpStatus.CREATED).build();
	}
	
	@ApiOperation(
			value="Create project (anonymous user).",
			notes="Create a new empty project for the most recent sheet of a mission."
	)
	@ApiResponses({
		@ApiResponse(code = 201, message="New project created."),
		@ApiResponse(code = 401, message="Invalid authentification token."),
		@ApiResponse(code = 403, message="Mission has status 'validated' or projects limit reached for the sheet."),
		@ApiResponse(code = 404, message="Mission not found.")
	})
	@PreAuthorize("hasAuthority('MISSION')")
	@GetMapping("/new-project-anonymous")
	public ResponseEntity<?> newProjectAnonymous() {
		Long missionId = ((Mission)(SecurityContextHolder.getContext().getAuthentication().getPrincipal())).getId();
		try { _newProject(missionId); }
		catch (ResponseEntityException e) {
			return e.buildResponse();
		}
		return ResponseEntity.status(HttpStatus.CREATED).build();
	}
	
	/**
	 * Function that creates a new project for a given mission if the mission is modifiable and has less than 5 projects.
	 * @param missionId Id of the mission
	 * @throws ResponseEntityException exception thrown if the mission is not found, the mission has already 5 projects or is validated.
	 */
	private void _newProject(Long missionId) throws ResponseEntityException {
		Mission mission = dal.findById(missionId)
				.orElseThrow(() -> new ResourceNotFoundException(Mission.class));
		if (mission.isValidated())
			throw new InvalidSheetStatusException();
		MissionSheet lastVersion = mission.getLastVersion();
		if (!(lastVersion.getProjects().size() < 5))
			throw new ProjectsBoundsException();
		Project newProject = new Project(lastVersion);
		dal.addProjectForSheet(lastVersion, newProject);
	}
	
	@ApiOperation(
			value="Update a project's field.",
			notes="Update the value of one of the fields of a project."
	)
	@ApiResponses({
		@ApiResponse(code = 200, message="Project's field updated."),
		@ApiResponse(code = 400, message="Inexistant fieldname or invalid value."),
		@ApiResponse(code = 401, message="Invalid authentification token."),
		@ApiResponse(code = 403, message="User isn't a manager or mission linked to the project has status 'validated' or project isn't linked to the most recent sheet of the mission."),
		@ApiResponse(code = 404, message="Project not found.")
	})
	@PreAuthorize("hasAuthority('MANAGER')")
	@PutMapping("projects")
	public ResponseEntity<?> putProject(
			@ApiParam(
					"id : project's identifier;\n"
					+ "fieldName : project fieldname to update;\n"
					+ "value : field's new value."
			)
			@Valid @RequestBody UpdateEntityRequest request) {
		return updateProject(request.getId(), request.getFieldName(), request.getValue());
	}
	
	@ApiOperation(
			value="Update a project's field (anonymous user).",
			notes="Update the value of one of the fields of a project."
	)
	@ApiResponses({
		@ApiResponse(code = 200, message="Project's field updated."),
		@ApiResponse(code = 400, message="Inexistant fieldname or invalid value."),
		@ApiResponse(code = 401, message="Invalid authentification token."),
		@ApiResponse(code = 403, message="Mission linked to the project has status 'validated' or project isn't linked to the most recent sheet of the mission."),
		@ApiResponse(code = 404, message="Project not found.")
	})
	@PreAuthorize("hasAuthority('MISSION')")
	@PutMapping("projects/anonymous")
	public ResponseEntity<?> putProjectFromToken(
			@ApiParam(
					"id : project's identifier;\n"
					+ "fieldName : project's fieldname to update;\n"
					+ "value : field's new value."
			)
			@Valid @RequestBody UpdateEntityRequest request) {
		Long missionId = ((Mission)(SecurityContextHolder.getContext().getAuthentication().getPrincipal())).getId();
		try {
			Project project = dal.findProjectById(request.getId())
					.orElseThrow(() -> new ResourceNotFoundException(Project.class));
			if (project.getMissionSheet().getMission().getId() != missionId)
				return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		} catch (ResourceNotFoundException e) {
			return e.buildResponse();
		}
		return updateProject(request.getId(), request.getFieldName(), request.getValue());
	}
	
	/**
	 * Function that will modify a specific field of a project.
	 * @param id ID of the project to be modified
	 * @param key name of the field that will be modified
	 * @param value the modified value of the field
	 * @return 200 The project is updated <br>403 The sheet status is not good<br>404 The project is not found<br>400 The field name is not good<br>
	 */
	private ResponseEntity<?> updateProject(Long id, String key, Object value){
		try {
			Project project = dal.findProjectById(id)
					.orElseThrow(() -> new ResourceNotFoundException(Project.class));
			if (project.getMissionSheet().getMission().isValidated())
				throw new InvalidSheetStatusException();
			checkIfProjectOfLastVersion(project);
			EProjectFieldname fieldName;
			try { fieldName = EProjectFieldname.valueOf(key); }
			catch (IllegalArgumentException e) { throw new InvalidFieldnameException(); }
			switch(fieldName) {
			case title:
				project.setTitle((String)value);
				break;
			case description:
				project.setDescription((String)value);
				break;
			case beginDate:
				project.setBeginDate(LocalDate.parse((String)value));
				break;
			case endDate:
				project.setEndDate(LocalDate.parse((String)value));
				break;
			default: 
				throw new InvalidFieldnameException();
			}
			updateSheetStatus(project.getMissionSheet().getMission());
			dal.saveProject(project);
			return ResponseEntity.ok(null);
		} catch (ResponseEntityException e) { 
			return e.buildResponse();
		} catch (ClassCastException | NullPointerException e) {
			return new InvalidValueException().buildResponse();
		}
	}
	
	@ApiOperation(value="Delete project.")
	@ApiResponses({
		@ApiResponse(code = 200, message="Project deleted."),
		@ApiResponse(code = 401, message="Invalid authentification token."),
		@ApiResponse(code = 403, message="User isn't a manager or mission linked to the project has status 'validated' "
				+ "or project isn't linked to the most recent sheet of the mission or only projet linked to the sheet."),
		@ApiResponse(code = 404, message="Project not found.")
	})
	@PreAuthorize("hasAuthority('MANAGER')")
	@DeleteMapping("projects/{projectId}")
	public ResponseEntity<?> deleteProject(
			@ApiParam("Project identifier.")
			@PathVariable Long projectId) {
		try { projectDeletion(projectId); }
		catch (ResponseEntityException e) {
			return e.buildResponse();
		}
		return ResponseEntity.ok(null);
	}
	
	@ApiOperation(value="Delete project (anonymous user).")
	@ApiResponses({
		@ApiResponse(code = 200, message="Project deleted."),
		@ApiResponse(code = 401, message="Invalid authentification token."),
		@ApiResponse(code = 403, message="Mission linked to the project has status 'validated' "
				+ "or project isn't linked to the most recent sheet of the mission or only projet linked to the sheet."),
		@ApiResponse(code = 404, message="Project not found.")
	})
	@PreAuthorize("hasAuthority('MISSION')")
	@DeleteMapping("projects/anonymous/{projectId}")
	public ResponseEntity<?> deleteProjectFromToken(
			@ApiParam("Project identifier.")
			@PathVariable Long projectId) {
		try {
			Long missionId = ((Mission)(SecurityContextHolder.getContext().getAuthentication().getPrincipal())).getId();
			Project project = dal.findProjectById(projectId)
					.orElseThrow(() -> new ResourceNotFoundException(Project.class));
			if (project.getMissionSheet().getMission().getId() != missionId)
				return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
			projectDeletion(projectId);
		} catch (ResponseEntityException e) {
			return e.buildResponse();
		}
		return ResponseEntity.ok(null);
	}
	
	/**
	 * Function that will delete a project by using its ID.
	 * @param id ID of the project
	 * @throws ResponseEntityException exception thrown if the project is not found
	 */
	private void projectDeletion(Long id) throws ResponseEntityException {
			Project project = dal.findProjectById(id)
					.orElseThrow(() -> new ResourceNotFoundException(Project.class));
			if (project.getMissionSheet().getMission().isValidated())
				throw new InvalidSheetStatusException();
			if (!(project.getMissionSheet().getMission().getLastVersion().getProjects().size() > 1))
				throw new ProjectsBoundsException();
			checkIfProjectOfLastVersion(project);
			removeSkillFromDeletionProject(project);
			this.storeImage.delete(StoreImage.PROJECT_FOLDER+project.getPicture());
			dal.removeProject(project);
	}
	
	/**
	 * Function that will remove the skills from a project and remove the skills from this project and totally delete the skills if they don't have any other project.
	 * @param project the project which skills will be removed
	 */
	private void removeSkillFromDeletionProject(Project project) {
		Set<Skill> skillsCopy = new HashSet<>();
		for(Skill skill : project.getSkills()) {
			skillsCopy.add(skill);
		}
		for(Skill skill : skillsCopy) {
			this.dal.removeSkillFromProject(project, skill);
		}
	}
	
	/**
	 * Function that verifies if the given project is up to date.
	 * @param project the project that will be verified
	 * @throws NotLastVersionException exception thrown if the project is not up to date
	 */
	private void checkIfProjectOfLastVersion(Project project) throws NotLastVersionException {
		if (project.getMissionSheet().getMission().getLastVersion().getId() != project.getMissionSheet().getId())
			throw new NotLastVersionException();
	}
	
	/**
	 * Function that modifies the status of the mission "on waiting" to "on going".
	 * @param mission the mission that will be modified
	 */
	private void updateSheetStatus(Mission mission) {
		if (mission.getSheetStatus().equals(ESheetStatus.ON_WAITING)) {
			mission.setSheetStatus(ESheetStatus.ON_GOING);
			dal.save(mission);
		}
	}
	
	/**
	 * Function that verifies if a given date is the date of today.
	 * @param date a date from the class LocalDate
	 * @return true if the given date is today or false
	 */
	private boolean isToday(LocalDate date) {
		LocalDate today = LocalDate.now();
		
		return today.getDayOfYear() == date.getDayOfYear() 
				&& today.getYear() == date.getYear();
	}
	
	
	@ApiOperation(
			value = "Link an image to a project.",
			notes = "It takes a file in the body, then copies it in the server in the img/proj folder eand add the name to the "
					+ "projet in the database. The request must be asked by a manager."
	)
	@ApiResponses({
		@ApiResponse(code = 401, message="Invalid authentification token."),
		@ApiResponse(code = 404, message="Project is not found"),
		@ApiResponse(code = 200, message="Image is uploaded and project line is modified with the new image name in database.")
	})
	@PreAuthorize("hasAuthority('MANAGER')")
	@PostMapping("/projects/{id}/upload-picture")
	public ResponseEntity<?> uploadLogoManager(@RequestParam("file") MultipartFile file, @PathVariable Long id) {
		return this.uploadPicture(file, id);
	}
	
	@ApiOperation(
			value = "Link an image to a project by anonymous token.",
			notes = "It takes a file in the body, then copies it in the server in the img/proj folder eand add the name to the "
					+ "projet in the database. The request must be asked by an anonymous user with a valid token."
	)
	@ApiResponses({
		@ApiResponse(code = 401, message="Invalid  token."),
		@ApiResponse(code = 404, message="Project is not found"),
		@ApiResponse(code = 200, message="Image is uploaded and project line is modified with the new image name in database."),
		@ApiResponse(code = 400, message="The extension of the file is not good.")
	})
	@PreAuthorize("hasAuthority('MISSION')")
	@PostMapping("/projects/anonymous/{id}/upload-picture")
	public ResponseEntity<?> uploadLogoToken(@RequestParam("file") MultipartFile file, @PathVariable Long id) {
		Long missionId = ((Mission)(SecurityContextHolder.getContext().getAuthentication().getPrincipal())).getId();
		try {
			Project project = dal.findProjectById(id)
					.orElseThrow(() -> new ResourceNotFoundException(Project.class));
			if (project.getMissionSheet().getMission().getId() != missionId)
				return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		} catch (ResourceNotFoundException e) {
			return e.buildResponse();
		}
		return this.uploadPicture(file, id);
	}
	
	/**
	 * Upload a project picture on server side and and the file name to the project.
	 * @param file  project picture (weight is less than 1 Mo)
	 * @param id  project id
	 * @return 404 if the project is not found<br>200 if the image is added<br>400 if the extension of the file is not good
	 */
	private ResponseEntity<?> uploadPicture(MultipartFile file, Long id){
		try {
			String extension = FilenameUtils.getExtension(file.getOriginalFilename());
			if(extension.equals("jpg") ||
			   extension.equals("JPG") ||
			   extension.equals("png") ||
			   extension.equals("PNG") ||
			   extension.equals("jpeg") ||
			   extension.equals("JPEG") ||
			   extension.equals("gif") ||
			   extension.equals("GIF") ||
			   extension.equals("webp") ||
			   extension.equals("WEBP") ||
			   extension.equals("ico") ||
			   extension.equals("ICO")) {
				Project proj = this.dal.findProjectById(id).orElseThrow(() -> new ResourceNotFoundException(Project.class));
				if(proj.getPicture()!=null) {
					this.storeImage.delete("img/proj/"+proj.getPicture());
					proj.setPicture(null);
				}
				storeImage.save(file,"project");
				proj.setPicture(file.getOriginalFilename());
				this.dal.saveProject(proj);
				return ResponseEntity.status(HttpStatus.OK).build();
			}
			else {
				return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
			}
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.EXPECTATION_FAILED).build();
		}
	}
	
	@ApiOperation(
			value = "Delete an image from a project (manager user).",
			notes = "It sets to null the picture value in the line of the project in the database, and delete from the server the picture file. "
					+ "The request must be done by an anonymous user."
	)
	@ApiResponses({
		@ApiResponse(code = 401, message="Invalid token."),
		@ApiResponse(code = 404, message="Project is not found"),
		@ApiResponse(code = 200, message="Image is deleted and project line is modified with null value in database.")
	})
	@PreAuthorize("hasAuthority('MANAGER')")
	@DeleteMapping("/projects/{id}/delete-picture")
	public ResponseEntity<?> deletePictureManager(@PathVariable Long id) {
		return this.deletePicture(id);
	}
	
	@ApiOperation(
			value = "Delete an image from a project (anonymous user).",
			notes = "It sets to null the picture value in the line of the project in the database, and delete from the server the picture file. "
					+ "The request must be done by a manager."
	)
	@ApiResponses({
		@ApiResponse(code = 401, message="Invalid token."),
		@ApiResponse(code = 404, message="Project is not found"),
		@ApiResponse(code = 200, message="Image is deleted and project line is modified with null value in database.")
	})
	@PreAuthorize("hasAuthority('MISSION')")
	@DeleteMapping("/projects/anonymous/{id}/delete-picture")
	public ResponseEntity<?> deletePictureToken(@PathVariable Long id) {
		Long missionId = ((Mission)(SecurityContextHolder.getContext().getAuthentication().getPrincipal())).getId();
		try {
			Project project = dal.findProjectById(id)
					.orElseThrow(() -> new ResourceNotFoundException(Project.class));
			if (project.getMissionSheet().getMission().getId() != missionId)
				return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		} catch (ResourceNotFoundException e) {
			return e.buildResponse();
		}
		return this.deletePicture(id);
	}
	
	/**
	 * Delete the picture linked to a project  from server. It also sets to null the name of file in the database.
	 * @param projectId project id
	 * @return 200 if deletion is done<br>404 if project is not found
	 */
	private ResponseEntity<?> deletePicture(Long projectId){
		try {
			Project proj = this.dal.findProjectById(projectId)
				.orElseThrow(() -> new ResourceNotFoundException(Project.class));
			if(proj.getPicture()!=null) {
				this.storeImage.delete("img/proj/"+proj.getPicture());
				proj.setPicture(null);
			}
			this.dal.saveProject(proj);
		} catch (ResourceNotFoundException e) {
			return e.buildResponse();
		}
		return ResponseEntity.status(HttpStatus.OK).build();
	}
	
	@ApiOperation(
			value = "Get a project image.",
			notes = "The picture name is passed as a parameter and the file is returned."
	)
	@ApiResponses({
		@ApiResponse(code = 401, message="Invalid token."),
		@ApiResponse(code = 404, message="No image is found with this name"),
		@ApiResponse(code = 200, message="An image is found and returned")
	})
	@GetMapping("/projects/picture/{fileName:.+}")
    public ResponseEntity<Resource> downloadFile(@PathVariable String fileName, HttpServletRequest request) {
        // Load file as Resource
        Resource resource = storeImage.loadFileAsResource(fileName,"project");
        
        if(resource == null) {
        	return ResponseEntity.notFound().build();
        }
        
        // Try to determine file's content type
        String contentType = null;
        try {
            contentType = request.getServletContext().getMimeType(resource.getFile().getAbsolutePath());
        } catch (IOException ex) {
        	System.err.println(ex);
        }
        
        if(contentType == null) {
        	contentType = "application/octet-stream";
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resource.getFilename() + "\"")
                .body(resource);
    }
	
	@ApiOperation(
			value = "Add skills to a project (manager user).",
			notes = "It adds to the given projet some skills. For each skill, it is checked if that one exists, if so the found one will be used. "
					+ "If none is found, a new skill is created."
					+ "The request must be done by a manager."
	)
	@ApiResponses({
		@ApiResponse(code = 401, message="Invalid token."),
		@ApiResponse(code = 404, message="Project is not found"),
		@ApiResponse(code = 200, message="Skills are created and added to the project.")
	})
	@PreAuthorize("hasAuthority('MANAGER')")
	@PostMapping("/projects/{id}/skills")
	public ResponseEntity<?> addSkillToProjectManager(@PathVariable Long id, @RequestBody String... labels) {
		return this.addSkillToProject(id, labels);
	}
	
	@ApiOperation(
			value = "Add skills to a project (anonymous user).",
			notes = "It adds to the given projet some skills. For each skill, it is checked if that one exists, if so the found one will be used. "
					+ "If none is found, a new skill is created."
					+ "The request must be done by an anonymous user."
	)
	@ApiResponses({
		@ApiResponse(code = 401, message="Invalid token."),
		@ApiResponse(code = 404, message="Project is not found"),
		@ApiResponse(code = 200, message="Image is deleted and project line is modified with null value in database.")
	})
	@PreAuthorize("hasAuthority('MISSION')")
	@PostMapping("/projects/anonymous/{id}/skills")
	public ResponseEntity<?> addSkillToProjectToken(@PathVariable Long id, @RequestBody String... labels) {
		Long missionId = ((Mission)(SecurityContextHolder.getContext().getAuthentication().getPrincipal())).getId();
		try {
			Project project = dal.findProjectById(id)
					.orElseThrow(() -> new ResourceNotFoundException(Project.class));
			if (project.getMissionSheet().getMission().getId() != missionId)
				return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		} catch (ResourceNotFoundException e) {
			return e.buildResponse();
		}
		return this.addSkillToProject(id, labels);
	}
	
	/**
	 * Add skills to a project. It creates if needed the skills, or else it find the existing one to use it.
	 * @param id  project id
	 * @param labels  array of skill labels
	 * @return 404 if the project is not found<br>200 if all skills are added to the project
	 */
	private ResponseEntity<?> addSkillToProject(Long id, String... labels) {
		try {
			Project proj = this.dal.findProjectById(id)
					.orElseThrow(() -> new ResourceNotFoundException(Project.class));
			for(int i=0;i<labels.length;i++) {
				Skill s = this.dal.findSkillByLabel(labels[i]).orElse(new Skill(labels[i]));
				this.dal.addSkillToProject(proj, s);
			}
			return ResponseEntity.ok().build();
		} catch (ResourceNotFoundException e) {
			return e.buildResponse();
		}
	}
	
	@ApiOperation(
			value = "Delete a skill from a project (manager user).",
			notes = "It adds to the given projet some skills. For each skill, it is checked if that one exists, if so the found one will be used. "
					+ "If none is found, a new skill is created."
					+ "The request must be done by a manager."
	)
	@ApiResponses({
		@ApiResponse(code = 401, message="Invalid token."),
		@ApiResponse(code = 404, message="Project is not found or skill is not found"),
		@ApiResponse(code = 200, message="Skills are created and added to the project.")
	})
	@PreAuthorize("hasAuthority('MANAGER')")
	@DeleteMapping("/projects/{id}/skills")
	public ResponseEntity<?> removeSkillFromProjectManager(@PathVariable Long id, @RequestBody Skill skill) {
		return this.removeSkillFromProject(id, skill);
	}
	
	@ApiOperation(
			value = "Delete a skill from a project (anonymous user).",
			notes = "It adds to the given projet some skills. For each skill, it is checked if that one exists, if so the found one will be used. "
					+ "If none is found, a new skill is created."
					+ "The request must be done by an anonymous user."
	)
	@ApiResponses({
		@ApiResponse(code = 401, message="Invalid token."),
		@ApiResponse(code = 404, message="Project is not found or skill is not found"),
		@ApiResponse(code = 200, message="Skills are created and added to the project.")
	})
	@PreAuthorize("hasAuthority('MISSION')")
	@DeleteMapping("/projects/anonymous/{id}/skills")
	public ResponseEntity<?> removeSkillFromProjectToken(@PathVariable Long id, @RequestBody Skill skill) {
		Long missionId = ((Mission)(SecurityContextHolder.getContext().getAuthentication().getPrincipal())).getId();
		try {
			Project project = dal.findProjectById(id)
					.orElseThrow(() -> new ResourceNotFoundException(Project.class));
			if (project.getMissionSheet().getMission().getId() != missionId)
				return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		} catch (ResourceNotFoundException e) {
			return e.buildResponse();
		}
		return this.removeSkillFromProject(id, skill);
	}
	
	/**
	 * Delete a skill from  a project. If the skill is no longer attached to a project, it will be also deleted.
	 * @param id  project id
	 * @param skill  skill object
	 * @return 404 if the project or the skill is not found<br>200 if the deletion is done
	 */
	private ResponseEntity<?> removeSkillFromProject(Long id, Skill skill) {
		try {
			Project project = this.dal.findProjectById(id)
					.orElseThrow(() -> new ResourceNotFoundException(Project.class));
			Skill s = this.dal.findSkillByLabel(skill.getLabel())
					.orElseThrow(() -> new ResourceNotFoundException(Skill.class));
			this.dal.removeSkillFromProject(project, s);
			return ResponseEntity.ok().build();
		} catch (ResourceNotFoundException e) {
			return e.buildResponse();
		}
	}
	
	@ApiOperation(value = "Find all skills.")
	@ApiResponses({
		@ApiResponse(code = 401, message="Invalid token."),
		@ApiResponse(code = 200, message="List of skills can be returned.")
	})
	@GetMapping("/projects/skills-all")
	public ResponseEntity<?> getAllSkills() {
		return ResponseEntity.ok(this.dal.findAllSkills());
	}
	
	
	@ApiOperation(
			value = "Generate and send a pdf document.",
			notes = "Given a list of projects and missions, it sends back a pdf document with a page for each project and mission."
	)
	@ApiResponses({
		@ApiResponse(code = 201, message="PDF was created and can be sent."),
		@ApiResponse(code = 401, message="Invalid authentification token."),
		@ApiResponse(code = 400, message="PDF couldn't be created or sent."),
		@ApiResponse(code = 404, message="Project or mission not found.")
	})
	@PostMapping("/pdf")
	public ResponseEntity<?> generatePDF(@Valid @RequestBody List<GeneratePDFRequest> elements ) {
		
		
			int n = elements.size();
		
			PDDocument document = new PDDocument();
			List<Integer> missionIndex = new ArrayList<Integer>();
			
			PDFGenerator pdfGenerator;
			try {
				pdfGenerator = new PDFGenerator(document);
			
			
				for(int i = 0; i<n ; i++) {
					
						
							if(elements.get(i).getType().equals("m")){
								Mission mission = dal.findById(elements.get(i).getId())
										.orElseThrow(() -> new ResourceNotFoundException(Mission.class));
								pdfGenerator.makeMissionPDF(mission,document);
								missionIndex.add(i);
							
							}
				}
				
				
				if(missionIndex.size()>0) {
					Set<Project> projectsToAdd = new HashSet<Project>();
					Set<Project> projectsAlreadyAdded = new HashSet<Project>();
					for(Integer index : missionIndex) {
						
						
						Long id = elements.get(index).getId();
						PDPage missionToMove = document.getPage(0);
						document.removePage(0);
						document.addPage(missionToMove);
						for(int i = 0; i<n ; i++) {
							if(elements.get(i).getType().equals("p")) {
								Project project = dal.findProjectById(elements.get(i).getId())
										.orElseThrow(() -> new ResourceNotFoundException(Project.class));
								if(project.getMissionSheet().getMission().getId()==id) {
									
									if(projectsToAdd.contains(project)) {
										projectsToAdd.remove(project);
									}
									
									pdfGenerator.makeProjectPDF(project,document);
									projectsAlreadyAdded.add(project);
								}
								else if(!projectsAlreadyAdded.contains(project)) {
									
									projectsToAdd.add(project);
									
								}
							}
						}
					}
					
					for(Project project : projectsToAdd) {
						pdfGenerator.makeProjectPDF(project,document);
					}
				}
				else {
					for(int i = 0; i<n ; i++) {
						
						
						if(elements.get(i).getType().equals("p")){
							Project project = dal.findProjectById(elements.get(i).getId())
									.orElseThrow(() -> new ResourceNotFoundException(Project.class));
							pdfGenerator.makeProjectPDF(project,document);
						}
					}
				}
				
			} catch (ResourceNotFoundException e) {
				return e.buildResponse();
			} catch (IOException e) {
				return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("the file could not be created");
			} 
			
			try {
				pdfGenerator.saveFinalPDF(document);
			} catch (IOException e) {
				return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("the file could not be saved");
			}
			
			Path path = Paths.get("pdf\\fichesMissionsEtProjets.pdf");
			byte[] data;
			try {
				data = Files.readAllBytes(path);
			} catch (IOException e) {
				return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("the file could not be saved");
			}
	        ByteArrayResource resource = new ByteArrayResource(data);
			
	        return ResponseEntity.status(HttpStatus.CREATED)
	        		.header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=pdf\\fichesMissionsEtProjets.pdf")
	        		.contentType(MediaType.APPLICATION_PDF) 
	                .contentLength(data.length) 
	                .body(resource);
	}
}
