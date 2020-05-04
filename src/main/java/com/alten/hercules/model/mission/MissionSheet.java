package com.alten.hercules.model.mission;

import java.util.Date;

import javax.persistence.Column;
import javax.persistence.EmbeddedId;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.validation.constraints.Min;

import org.hibernate.validator.constraints.Length;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
public class MissionSheet {
	
	@JsonIgnore
	@EmbeddedId MissionSheetId id;
	
	@Column(nullable = true)
	private String title = null;
	
	@Column(nullable = true)
	@Length(max = 1000)
	private String description = null;
	
	@Column(nullable = true)
	private String comment = null;
	
	@Column(nullable = true)
	private String city;
	
	@Column(nullable = true)
	private String country;
	
	@Min(0)
	@Column(nullable = true)
	private Integer consultantStartXp;
	
	@Min(1)
	@Column(nullable = true)
	private Integer teamSize;
	
	@Column(nullable = true)
	@Enumerated(EnumType.STRING)
	private EContractType contractType;
	
	public MissionSheet() {}
	
	public MissionSheet(Mission mission) {
		this.id = new MissionSheetId(mission, new Date());
	}

	public MissionSheetId getId() { return id; }
	public void setId(MissionSheetId id) { this.id = id; }

	public String getTitle() { return title; }
	public void setTitle(String title) { this.title = title; }
	
	public String getDescription() { return description; }
	public void setDescription(String description) { this.description = description; }

	public String getComment() { return comment; }
	public void setComment(String comment) { this.comment = comment; }
	
	public String getCity() { return city; }
	public void setCity(String city) { this.city = city; }

	public String getCountry() { return country; }
	public void setCountry(String country) { this.country = country; }

	public Integer getConsultantStartXp() { return consultantStartXp; }
	public void setConsultantStartXp(int consultantStartExp) { this.consultantStartXp = consultantStartExp; }
	
	public EContractType getContractType() { return contractType; }
	public void setContractType(EContractType type) { this.contractType = type; }

	public Integer getTeamSize() { return teamSize; }
	public void setTeamSize(int teamSize) { this.teamSize = teamSize; }
	
	@JsonGetter("date")
    private Date getVersionDate() { return id.getVersionDate(); }

}
