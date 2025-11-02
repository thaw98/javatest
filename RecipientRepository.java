package com.grppj.donateblood.repository;

import com.grppj.donateblood.model.AppointmentStatus;
import com.grppj.donateblood.model.BloodRequest;
import com.grppj.donateblood.model.Urgency;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class RecipientRepository {

    @Autowired
    private BloodStockRepository bloodStockRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /* ------------ LISTING ------------- */
    public List<RecipientRow> listRecipientRequestsForHospital(Integer hospitalId) {
        String sql = """
            SELECT
                br.id                 AS request_id,
                br.quantity           AS quantity,
                br.status             AS status,
                br.required_date      AS required_date,
                br.request_date       AS request_date,
                br.urgency            AS urgency,
                br.hospital_id        AS hospital_id,
                br.blood_type_id      AS blood_type_id,
                br.target_hospital_id AS target_hospital_id,      -- NEW

                u.username            AS username,
                u.email               AS email,
                u.phone               AS phone,
                u.gender              AS gender,
                u.dateofbirth         AS date_of_birth,
                u.address             AS address,

                bt.blood_type         AS blood_type,
                h.hospital_name       AS hospital_name,
                th.hospital_name      AS target_hospital_name      -- NEW
            FROM blood_request br
            LEFT JOIN `user` u      ON u.id = br.user_id
            JOIN blood_type bt      ON bt.id = br.blood_type_id
            JOIN hospital h         ON h.id = br.hospital_id
            LEFT JOIN hospital th   ON th.id = br.target_hospital_id  -- NEW
            WHERE (? IS NULL OR br.hospital_id = ?)
            ORDER BY br.required_date DESC, br.id DESC
        """;

        return jdbcTemplate.query(sql, (rs, rn) -> {
            RecipientRow row = mapRow(rs);

            Integer availableUnits = jdbcTemplate.queryForObject("""
                    SELECT COALESCE(SUM(d.blood_unit), 0)
                      FROM donation d
                      JOIN donor_appointment da ON da.id = d.donor_appointment_id
                     WHERE da.hospital_id   = ?
                       AND da.blood_type_id = ?
                       AND d.status = 'Available'
                    """,
                    Integer.class,
                    row.getHospitalId(),
                    row.getBloodTypeId()
            );

            row.setCanComplete(availableUnits != null && availableUnits >= row.getQuantity());
            return row;
        }, hospitalId, hospitalId);
    }

    private RecipientRow mapRow(ResultSet rs) throws SQLException {
        RecipientRow r = new RecipientRow();
        r.setRequestId(rs.getInt("request_id"));
        r.setQuantity(rs.getInt("quantity"));
        r.setStatus(rs.getString("status"));
        try {
            var ts = rs.getTimestamp("request_date");
            r.setRequestDate(ts != null ? ts.toLocalDateTime() : null);
        } catch (Exception ignore) {
            var d = rs.getDate("request_date");
            r.setRequestDate(d != null ? d.toLocalDate().atStartOfDay() : null);
        }
        try {
            var ts = rs.getTimestamp("required_date");
            r.setRequiredDate(ts != null ? ts.toLocalDateTime() : null);
        } catch (Exception ignore) {
            var d = rs.getDate("required_date");
            r.setRequiredDate(d != null ? d.toLocalDate().atStartOfDay() : null);
        }

        r.setUrgency(rs.getString("urgency"));
        r.setHospitalId(rs.getInt("hospital_id"));
        r.setBloodTypeId(rs.getInt("blood_type_id"));

        // NEW: transferred-to hospital
        r.setTargetHospitalId((Integer) rs.getObject("target_hospital_id")); // null-safe
        r.setTargetHospitalName(rs.getString("target_hospital_name"));

        r.setUsername(rs.getString("username"));
        r.setEmail(rs.getString("email"));
        r.setPhone(rs.getString("phone"));
        r.setGender(rs.getString("gender"));
        r.setAddress(rs.getString("address"));
        r.setDateOfBirth(rs.getString("date_of_birth"));
        r.setBloodType(rs.getString("blood_type"));
        r.setHospitalName(rs.getString("hospital_name"));
        return r;
    }

    public Integer findHospitalIdForRequest(int requestId) {
        return jdbcTemplate.queryForObject(
            "SELECT hospital_id FROM blood_request WHERE id = ?",
            Integer.class,
            requestId
        );
    }

    /* ------------ CREATE/UPDATE HELPERS (Admin Add) ------------- */

    public Integer ensureUserAndGetId(String name,
                                      String email,
                                      String rawPassword,
                                      String phone,
                                      String dob,
                                      String address,
                                      String gender,
                                      Integer explicitRoleId) {

        Integer userId = jdbcTemplate.query(
                "SELECT id FROM `user` WHERE email = ?",
                rs -> rs.next() ? rs.getInt(1) : null,
                email != null ? email.trim() : null
        );

        Integer recipientRoleId = explicitRoleId;
        if (recipientRoleId == null) {
            recipientRoleId = jdbcTemplate.query(
                "SELECT id FROM role WHERE LOWER(role) IN ('recipient','user','patient') ORDER BY id LIMIT 1",
                rs -> rs.next() ? rs.getInt(1) : null
            );
            if (recipientRoleId == null) {
                recipientRoleId = jdbcTemplate.query(
                    "SELECT id FROM role ORDER BY id LIMIT 1",
                    rs -> rs.next() ? rs.getInt(1) : null
                );
            }
        }
        if (userId == null) {
            jdbcTemplate.update(
                "INSERT INTO `user`(username, email, password, phone, dateofbirth, address, gender, role_id) " +
                "VALUES (?,?,?,?,?,?,?,?)",
                name,
                email,
                rawPassword,   // TODO: hash in production
                phone,
                dob,
                address,
                gender,
                recipientRoleId
            );

            userId = jdbcTemplate.queryForObject(
                "SELECT id FROM `user` WHERE email = ?",
                Integer.class,
                email.trim()
            );

        } else {
            jdbcTemplate.update(
                "UPDATE `user` SET username=?, phone=?, dateofbirth=?, address=?, gender=?, role_id=? WHERE id=?",
                name, phone, dob, address, gender, recipientRoleId, userId
            );
        }
        return userId;
    }

    public void insertBloodRequestAdmin(Integer userId,
                                        Integer hospitalId,
                                        Integer bloodTypeId,
                                        Integer quantity,
                                        Urgency urgency,
                                        AppointmentStatus status,
                                        Integer createdByAdminUserId,
                                        LocalDate requiredDate) {

        String urg = (urgency != null ? urgency.name() : "MEDIUM");
        String st  = (status  != null ? status.name().toLowerCase() : "pending"); // table uses lowercase

        jdbcTemplate.update("""
            INSERT INTO blood_request
                (quantity, request_date, required_date, urgency, status, user_id, hospital_id, blood_type_id)
            VALUES
                (?, NOW(), ?, ?, ?, ?, ?, ?)
            """,
            quantity,
            (requiredDate != null ? requiredDate : LocalDate.now()),
            urg,
            st,
            userId,
            hospitalId,
            bloodTypeId
        );

        if (createdByAdminUserId != null && createdByAdminUserId > 0) {
            jdbcTemplate.update("""
                UPDATE blood_request
                   SET created_by = ?
                 WHERE id = (SELECT MAX(id) FROM blood_request WHERE user_id=? AND hospital_id=?)
                """,
                createdByAdminUserId, userId, hospitalId
            );
        }
    }

    /* ------------ FULFILL/TRANSFER (existing) ------------- */

    public void updateStatusAndInsertFulfillment(int requestId,
                                                 int hospitalId,
                                                 int adminUserId,
                                                 int units) {

        Integer bloodTypeId = jdbcTemplate.queryForObject(
            "SELECT blood_type_id FROM blood_request WHERE id = ?",
            Integer.class, requestId
        );
        if (bloodTypeId == null || units <= 0) {
            jdbcTemplate.update("UPDATE blood_request SET status = 'completed' WHERE id = ?", requestId);
            return;
        }

        List<Integer> donationIds = jdbcTemplate.query(
            """
            SELECT d.donation_id
              FROM donation d
              JOIN donor_appointment da ON da.id = d.donor_appointment_id
             WHERE da.hospital_id   = ?
               AND da.blood_type_id = ?
               AND d.status         = 'Available'
             ORDER BY d.donation_date ASC, d.donation_id ASC
             LIMIT ?
            """,
            (rs, rn) -> rs.getInt(1),
            hospitalId, bloodTypeId, units
        );

        for (Integer did : donationIds) {
            jdbcTemplate.update(
                "UPDATE donation SET status = 'Used' WHERE donation_id = ? AND status = 'Available'",
                did
            );
            jdbcTemplate.update(
                """
                INSERT INTO request_fulfillment(fulfillment_date, quantity_used, donation_donation_id, blood_request_id)
                VALUES (NOW(), 1, ?, ?)
                """,
                did, requestId
            );
        }

        jdbcTemplate.update("UPDATE blood_request SET status = 'completed' WHERE id = ?", requestId);

        int consumed = donationIds.size();
        if (consumed > 0) {
            bloodStockRepository.decreaseStock(hospitalId, bloodTypeId, consumed, adminUserId, 0);
        }
    }

    /**
     * Transfers all units to another hospital.
     * Keeps the original quantity; marks the source as 'transferred' with target_hospital_id.
     */
    public void transferAllUnitsNoTx(int requestId, int targetHospitalId) {
        Integer qty = jdbcTemplate.query(
            "SELECT quantity FROM blood_request WHERE id = ?",
            rs -> rs.next() ? rs.getInt(1) : null,
            requestId
        );
        if (qty == null) throw new IllegalArgumentException("Request not found.");
        if (qty <= 0) throw new IllegalArgumentException("Nothing to transfer (quantity is 0).");

        int ins = jdbcTemplate.update("""
            INSERT INTO blood_request
                (quantity, request_date, required_date, urgency, status,
                 user_id, hospital_id, blood_type_id)
            SELECT
                ?, NOW(), br.required_date, br.urgency, 'pending',
                br.user_id, ?, br.blood_type_id
            FROM blood_request br
            WHERE br.id = ?
            """, qty, targetHospitalId, requestId);

        if (ins != 1) {
            throw new IllegalStateException("Could not create target request.");
        }

        // IMPORTANT: do NOT zero out quantity on the source request
        int upd = jdbcTemplate.update("""
            UPDATE blood_request
               SET status = 'transferred',
                   target_hospital_id = ?
             WHERE id = ?
               AND quantity = ?        -- optimistic concurrency
               AND status = 'pending'  -- avoid double-transfer
            """, targetHospitalId, requestId, qty);

        if (upd != 1) {
            jdbcTemplate.update("""
                DELETE FROM blood_request
                 WHERE hospital_id = ?
                   AND quantity = ?
                   AND status = 'pending'
                 ORDER BY id DESC
                 LIMIT 1
            """, targetHospitalId, qty);
            throw new IllegalStateException("Transfer failed while updating the source request. No changes kept.");
        }
    }
    

    /** Hospitals that currently have at least `minUnits` of this blood type available. */
    public List<Integer> hospitalsWithStock(int bloodTypeId, int minUnits) {
        String sql = """
            SELECT da.hospital_id
              FROM donation d
              JOIN donor_appointment da ON da.id = d.donor_appointment_id
             WHERE da.blood_type_id = ?
               AND d.status = 'Available'
             GROUP BY da.hospital_id
            HAVING COALESCE(SUM(d.blood_unit), 0) >= ?
            """;
        return jdbcTemplate.query(sql, (rs, rn) -> rs.getInt(1), bloodTypeId, minUnits);
    }


    /* ------------ DTO ------------- */
    public static class RecipientRow {
        private Integer requestId;
        private Integer quantity;
        private String  status;
        private LocalDateTime requiredDate;
        private LocalDateTime requestDate;
        private String  urgency;

        private Integer hospitalId;
        private Integer bloodTypeId;

        private String  username;
        private String  email;
        private String  phone;
        private String  gender;
        private String  dateOfBirth;   // VARCHAR(45)
        private String  address;

        private String  bloodType;
        private String  hospitalName;
        
        private java.util.List<Integer> eligibleTargetHospitalIds;

        public java.util.List<Integer> getEligibleTargetHospitalIds() { return eligibleTargetHospitalIds; }
        public void setEligibleTargetHospitalIds(java.util.List<Integer> v) { eligibleTargetHospitalIds = v; }
        // NEW: transferred-to hospital
        private Integer targetHospitalId;
        private String  targetHospitalName;

        private boolean canComplete;

        public Integer getRequestId() { return requestId; }
        public void setRequestId(Integer v) { requestId = v; }
        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer v) { quantity = v; }
        public String getStatus() { return status; }
        public void setStatus(String v) { status = v; }
        public LocalDateTime getRequiredDate() { return requiredDate; }
        public void setRequiredDate(LocalDateTime v) { requiredDate = v; }
        public LocalDateTime getRequestDate() { return requestDate; }
        public void setRequestDate(LocalDateTime v) { requestDate = v; }
        public String getUrgency() { return urgency; }
        public void setUrgency(String v) { urgency = v; }
        public Integer getHospitalId() { return hospitalId; }
        public void setHospitalId(Integer v) { hospitalId = v; }
        public Integer getBloodTypeId() { return bloodTypeId; }
        public void setBloodTypeId(Integer v) { bloodTypeId = v; }
        public String getUsername() { return username; }
        public void setUsername(String v) { username = v; }
        public String getEmail() { return email; }
        public void setEmail(String v) { email = v; }
        public String getPhone() { return phone; }
        public void setPhone(String v) { phone = v; }
        public String getGender() { return gender; }
        public void setGender(String v) { gender = v; }
        public String getDateOfBirth() { return dateOfBirth; }
        public void setDateOfBirth(String v) { dateOfBirth = v; }
        public String getAddress() { return address; }
        public void setAddress(String v) { address = v; }
        public String getBloodType() { return bloodType; }
        public void setBloodType(String v) { bloodType = v; }
        public String getHospitalName() { return hospitalName; }
        public void setHospitalName(String v) { hospitalName = v; }

        public boolean isCanComplete() { return canComplete; }
        public void setCanComplete(boolean v) { canComplete = v; }

        // NEW getters/setters
        public Integer getTargetHospitalId() { return targetHospitalId; }
        public void setTargetHospitalId(Integer v) { targetHospitalId = v; }
        public String getTargetHospitalName() { return targetHospitalName; }
        public void setTargetHospitalName(String v) { targetHospitalName = v; }
    }
    
    
    public BloodRequest findMessageById(int id) {
        String sql = """
            SELECT id, user_id, hospital_id, blood_type_id, quantity, required_date, urgency, status
              FROM blood_request
             WHERE id = ?
        """;

        List<BloodRequest> list = jdbcTemplate.query(sql, (rs, rowNum) -> {
            BloodRequest req = new BloodRequest();
            req.setId(rs.getInt("id"));
            req.setUserId(rs.getInt("user_id"));
            req.setHospitalId(rs.getInt("hospital_id"));
            req.setBloodTypeId(rs.getInt("blood_type_id"));
            req.setQuantity(rs.getInt("quantity"));
            req.setRequiredDate(rs.getDate("required_date").toLocalDate());

            String urgencyStr = rs.getString("urgency");
            if (urgencyStr != null) {
                req.setUrgency(Urgency.valueOf(urgencyStr.toUpperCase()));
            }

            String statusStr = rs.getString("status");
            if (statusStr != null) {
                req.setStatus(AppointmentStatus.valueOf(statusStr.toLowerCase()));
            }

            return req;
        }, id);

        return list.isEmpty() ? null : list.get(0);
    }
    
    public LocalDate getAppointmentDateById(int requestId) {
        String sql = "SELECT appointment_date FROM recipient_request WHERE id = ?";
        return jdbcTemplate.queryForObject(sql, LocalDate.class, requestId);
    }


}
