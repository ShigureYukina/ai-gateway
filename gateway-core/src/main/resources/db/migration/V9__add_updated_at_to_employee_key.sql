-- V9: Add updated_at column to employee_key
-- The EmployeeKeyEntity JPA model defines updatedAt with @PreUpdate,
-- but the original V7 DDL omitted this column.

ALTER TABLE employee_key
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
