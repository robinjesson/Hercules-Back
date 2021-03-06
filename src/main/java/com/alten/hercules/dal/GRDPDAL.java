package com.alten.hercules.dal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.alten.hercules.dao.consultant.ConsultantDAO;
import com.alten.hercules.dao.user.UserDAO;

/**
 * Layer to access the DAL needed to apply GRPD.
 * @author rjesson, mfoltz, abegue, jbaudot
 *
 */
@Service
public class GRDPDAL {
	
	/**
	 * DAO for consultants
	 */
	@Autowired private ConsultantDAO consultantDAO;
	
	/**
	 * DAO for users
	 */
	@Autowired private UserDAO userDAO;
	
	/**
	 * Apply GRDP to the users and consultants.
	 */
	public void applyGRDP() {
		consultantDAO.applyGRDP();
		userDAO.applyGRDP();
	}

}
