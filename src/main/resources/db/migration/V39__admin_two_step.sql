-- Two-step sign-in for admin accounts (authenticator app). The secret is stored encrypted; recovery codes only as hashes.
ALTER TABLE `admin_account` ADD COLUMN `totp_secret_enc` varchar(200) DEFAULT NULL;
ALTER TABLE `admin_account` ADD COLUMN `totp_enabled` bit(1) NOT NULL DEFAULT b'0';
ALTER TABLE `admin_account` ADD COLUMN `last_totp_step` bigint NOT NULL DEFAULT 0;
ALTER TABLE `admin_account` ADD COLUMN `recovery_code_hashes` varchar(600) DEFAULT NULL;
