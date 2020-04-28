package com.alten.hercules.dao.mission;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.alten.hercules.model.mission.OldMission;

public interface OldMissionDAO extends JpaRepository<OldMission, Long>{
	
	//@Query(value="INSERT INTO mission(id, consultant_id, customer_id, team_size, version) VALUES (?1, ?2, ?3,0,0)", nativeQuery = true)
	//public Mission fastInser(long id, long consultantId, long customerId);
	
	@Query(value="SELECT * FROM mission WHERE reference=?1 AND last_update = "
			+ "(SELECT MAX(last_update) FROM mission WHERE reference=?1)", nativeQuery = true)
	public Optional<OldMission> lastVersionByReference(Long reference);
	
	@Query(value="SELECT * FROM mission WHERE reference=?1 ", nativeQuery = true)
	public List<OldMission> byReference(Long reference);
	
	@Query(value="SELECT * FROM mission WHERE last_update IN "
			+ "(SELECT MAX(last_update) FROM mission GROUP BY reference)", nativeQuery = true)
	public List<OldMission> allMissionLastUpdate();
}
