package com.alten.hercules.dao.diploma;

import javax.transaction.Transactional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.alten.hercules.model.diploma.Diploma;

public interface DiplomaDAO extends JpaRepository<Diploma, Long>{
	
	@Query(value="DELETE FROM consultant_diplomas WHERE consultant_id=?1 AND diplomas_id=?2", nativeQuery = true)
	@Transactional
	@Modifying
	public void deleteConsultantDiploma(Long consultantId, Long diplomaId);
	
	@Query(value="INSERT INTO consultant_diplomas(consultant_id, diplomas_id) VALUES(?1,?2)", nativeQuery = true)
	@Transactional
	@Modifying
	public void insertConsultantDiploma(Long consultantId, Long diplomaId);
	
}