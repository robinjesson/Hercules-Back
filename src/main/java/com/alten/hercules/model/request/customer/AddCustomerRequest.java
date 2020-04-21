package com.alten.hercules.model.request.customer;

import javax.validation.constraints.NotBlank;

import com.alten.hercules.model.customer.Customer;

public class AddCustomerRequest {
	
	@NotBlank
	private String name;

	@NotBlank
	private String activitySector;
	
	private String description;
	
	public AddCustomerRequest(String name, String activitySector, String description) {
		this.name = name;
		this.activitySector = activitySector;
		this.description = description;
	}
	
	public String getName() { return name; }
	public void setName(String name) { this.name = name; }

	public String getActivitySector() { return activitySector; }
	public void setActivitySector(String activitySector) { this.activitySector = activitySector; }

	public String getDescription() { return description; }
	public void setDescription(String description) { this.description = description; }

	public Customer buildCustomer() {
		return new Customer(name, activitySector, description);
	}
}
