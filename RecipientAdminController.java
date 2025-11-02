package com.grppj.donateblood.controller;

import jakarta.servlet.http.HttpSession;
import lombok.Getter;
import lombok.Setter;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.grppj.donateblood.model.AppointmentStatus;
import com.grppj.donateblood.model.BloodRequest;
import com.grppj.donateblood.model.Urgency;
import com.grppj.donateblood.repository.BloodTypeRepository;
import com.grppj.donateblood.repository.HospitalRepository;
import com.grppj.donateblood.repository.RecipientRepository;
import com.grppj.donateblood.repository.UserMessageRepository;

import java.time.LocalDate;

@Controller
@RequestMapping("/admin")
public class RecipientAdminController {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final RecipientRepository recipientRepository;
    private final HospitalRepository hospitalRepository;
    private final BloodTypeRepository bloodTypeRepository;
    private final UserMessageRepository userMessageRepo;

    public RecipientAdminController(RecipientRepository recipientRepository,
                                    HospitalRepository hospitalRepository,
                                    BloodTypeRepository bloodTypeRepository,
                                    UserMessageRepository userMessageRepo) {
        this.recipientRepository = recipientRepository;
        this.hospitalRepository = hospitalRepository;
        this.bloodTypeRepository = bloodTypeRepository;
        this.userMessageRepo    =   userMessageRepo;
    }

 // RecipientAdminController.java
    @GetMapping("/recipients")
    public String recipients(Model model, HttpSession session) {
        Integer hospitalId = (Integer) session.getAttribute("HOSPITAL_ID");
        var rows = recipientRepository.listRecipientRequestsForHospital(hospitalId);

        var allHospitals = hospitalRepository.findAll();

        rows.forEach(row -> {
            // existing: attach hospital name
            allHospitals.stream()
                .filter(h -> h.getId() == row.getHospitalId())
                .findFirst()
                .ifPresent(h -> row.setHospitalName(h.getHospitalName()));

            // NEW: list of hospital IDs that have enough stock for this request
            var eligible = recipientRepository.hospitalsWithStock(
                    row.getBloodTypeId(),
                    row.getQuantity()
            );

            // optional: exclude the source hospital right here
            row.setEligibleTargetHospitalIds(
                eligible.stream()
                        .filter(id -> id != row.getHospitalId())
                        .toList()
            );
        });

        String hospitalTitle = (hospitalId == null)
                ? "All Hospitals"
                : allHospitals.stream()
                    .filter(h -> h.getId() == hospitalId)
                    .map(h -> h.getHospitalName())
                    .findFirst()
                    .orElse("Hospital " + hospitalId);

        model.addAttribute("rows", rows);
        model.addAttribute("hospitalTitle", hospitalTitle);
        model.addAttribute("hospitals", allHospitals);
        model.addAttribute("title", "Recipients");
        model.addAttribute("active", "recipients");
        model.addAttribute("userName",
                session.getAttribute("ADMIN_NAME") != null ? session.getAttribute("ADMIN_NAME") : "Admin");
        model.addAttribute("avatarUrl", session.getAttribute("ADMIN_AVATAR_URL"));

        return "admin/recipients";
    }

    /* ---------------------------
     * Add Blood Request (Admin)
     * --------------------------- */

    /** Show add form: /admin/recipients/add */
    @GetMapping("/recipients/add")
    public String showAddForm(Model model, HttpSession session) {
        AdminBloodRequestForm form = new AdminBloodRequestForm();
        form.setPassword("default123");
        // form.setUrgency(null); // leave null so "-- Select Urgency --" shows
        form.setRequiredDate(null);

        Integer hospitalId = (Integer) session.getAttribute("HOSPITAL_ID");
        if (hospitalId != null) {
            form.setHospitalId(hospitalId);
            var hospital = hospitalRepository.findById(hospitalId);
            model.addAttribute("hospital", hospital);
        } else {
            model.addAttribute("hospital", null);
        }

        model.addAttribute("form", form);
        model.addAttribute("hospitals", hospitalRepository.findAll());
        model.addAttribute("bloodTypes", bloodTypeRepository.findAll());
        model.addAttribute("title", "Add Blood Request");
        model.addAttribute("active", "add-recipient");
        model.addAttribute("userName",
            session.getAttribute("ADMIN_NAME") != null ? session.getAttribute("ADMIN_NAME") : "Admin");
        model.addAttribute("avatarUrl", session.getAttribute("ADMIN_AVATAR_URL"));

        return "admin/recipient-form";
    }

    /** Handle submit: /admin/recipients/add (POST) */
    @PostMapping("/recipients/add")
    public String submitAddForm(AdminBloodRequestForm form,
                                BindingResult binding,
                                HttpSession session,
                                RedirectAttributes ra,
                                Model model) {

        Integer sessionHospitalId = (Integer) session.getAttribute("HOSPITAL_ID");
        if (sessionHospitalId != null) form.setHospitalId(sessionHospitalId);

        boolean hasError = false;
        if (blank(form.getName()))  { binding.rejectValue("name","req","Name is required"); hasError = true; }
        if (blank(form.getEmail())) { binding.rejectValue("email","req","Email is required"); hasError = true; }
        if (blank(form.getPassword())) form.setPassword("default123");
        if (blank(form.getDob()))    { binding.rejectValue("dob","req","DOB is required"); hasError = true; }
        if (blank(form.getPhone())) {
            binding.rejectValue("phone","req","Phone number is required"); hasError = true;
        } else if (!form.getPhone().matches("^09\\d{7,11}$")) {
            binding.rejectValue("phone","fmt","Phone must start with 09 and contain 9–13 digits total"); hasError = true;
        }
        if (blank(form.getGender()))      { binding.rejectValue("gender","req","Gender is required"); hasError = true; }
        if (form.getBloodTypeId() == null){ binding.rejectValue("bloodTypeId","req","Blood Type is required"); hasError = true; }
        if (blank(form.getAddress()))     { binding.rejectValue("address","req","Address is required"); hasError = true; }
        if (form.getQuantity() == null || form.getQuantity() <= 0) {
            binding.rejectValue("quantity","min","Quantity must be > 0"); hasError = true;
        }
        if (form.getUrgency() == null)    { binding.rejectValue("urgency","req","Urgency is required"); hasError = true; }

        // NEW: required date
        java.time.LocalDate reqDate = null;
        if (blank(form.getRequiredDate())) {
            binding.rejectValue("requiredDate","req","Required date is required"); hasError = true;
        } else {
            try {
                reqDate = java.time.LocalDate.parse(form.getRequiredDate());
            } catch (java.time.format.DateTimeParseException e) {
                binding.rejectValue("requiredDate","fmt","Invalid date"); hasError = true;
            }
        }

        if (form.getHospitalId() == null) { binding.rejectValue("hospitalId","req","Hospital is required"); hasError = true; }

        if (hasError) {
            model.addAttribute("hospitals", hospitalRepository.findAll());
            model.addAttribute("bloodTypes", bloodTypeRepository.findAll());
            model.addAttribute("title", "Add Blood Request");
            model.addAttribute("active", "recipients");
            model.addAttribute("userName",
                session.getAttribute("ADMIN_NAME") != null ? session.getAttribute("ADMIN_NAME") : "Admin");
            model.addAttribute("avatarUrl", session.getAttribute("ADMIN_AVATAR_URL"));
            return "admin/recipient-form";
        }

        int adminUserId = (session.getAttribute("USER_ID") instanceof Integer)
                ? (Integer) session.getAttribute("USER_ID") : 0;

        Integer userId = recipientRepository.ensureUserAndGetId(
                form.getName(),
                form.getEmail(),
                form.getPassword(),
                form.getPhone(),
                form.getDob(),
                form.getAddress(),
                form.getGender(),
                null
        );

        recipientRepository.insertBloodRequestAdmin(
                userId,
                form.getHospitalId(),
                form.getBloodTypeId(),
                form.getQuantity(),
                form.getUrgency(),
                AppointmentStatus.pending,
                adminUserId,
                /* requiredDate */ reqDate   // <— use the parsed date
        );

        ra.addFlashAttribute("successMessage", "Blood request created.");
        return "redirect:/admin/recipients";
    }
    private boolean blank(String s) { return s == null || s.trim().isEmpty(); }

    @PostMapping("/recipients/{id}/complete")
    public String completeRequest(@PathVariable("id") int requestId,
                                  @RequestParam("bloodTypeId") int bloodTypeId,
                                  @RequestParam("quantity") int quantity,
                                  HttpSession session) {
        Integer hospitalId = (Integer) session.getAttribute("HOSPITAL_ID");
        if (hospitalId == null) {
            hospitalId = recipientRepository.findHospitalIdForRequest(requestId);
        }
        if (hospitalId != null && quantity > 0) {
            int adminUserId = (session.getAttribute("USER_ID") instanceof Integer)
                    ? (Integer) session.getAttribute("USER_ID") : 0;
            recipientRepository.updateStatusAndInsertFulfillment(requestId, hospitalId, adminUserId, quantity);
            
            String hospitalName = hospitalRepository.findNameById(hospitalId);
        
            BloodRequest request = recipientRepository.findMessageById(requestId);
            if (request != null) {
                int recipientUserId = request.getUserId();
                LocalDate appointmentDate = recipientRepository.getAppointmentDateById(requestId);

                String message = "Your blood request has been successfully fulfilled by " + hospitalName + ".\n\n"
                        + "Please come to the hospital to collect the blood on " + appointmentDate + " during our working hours.\n\n"
                        + "Thank you for placing your trust in " + hospitalName + ".";
                userMessageRepo.sendMessage(hospitalId, recipientUserId, message);
            }
        
        }
        return "redirect:/admin/recipients";
    }

    @PostMapping("/recipients/{id}/transfer")
    public String transferRequest(@PathVariable("id") int requestId,
                                  @RequestParam("targetHospitalId") int targetHospitalId,
                                  RedirectAttributes ra) {
        try {
            recipientRepository.transferAllUnitsNoTx(requestId, targetHospitalId);
            ra.addFlashAttribute("successMessage", "Request transferred to target hospital.");
        } catch (Exception e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/recipients";
    }

    @Getter @Setter
    public static class AdminBloodRequestForm {
        // User fields
        private String name;
        private String email;
        private String password;   // default: default123
        private String dob;        // VARCHAR in DB
        private String phone;      // must match ^09\d{7,11}$
        private String address;
        private String gender;     // "Male"/"Female"

        // Request fields
        private Integer bloodTypeId;
        private Integer quantity;
        private Urgency urgency;
        private String requiredDate;   // NEW: "yyyy-MM-dd" from <input type="date">


        // Context
        private Integer hospitalId;
    }
}
