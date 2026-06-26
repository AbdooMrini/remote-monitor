// ============================================================
// routes/auth.js
// ============================================================
'use strict';

const express   = require('express');
const router    = express.Router();
const ctrl      = require('../controllers/authController');
const { authenticateToken } = require('../middlewares/auth');
const { authLimiter }       = require('../middlewares/security');

router.post('/login',           authLimiter, ctrl.loginValidators, ctrl.login);
router.post('/logout',          authenticateToken, ctrl.logout);
router.post('/refresh',         ctrl.refresh);
router.post('/change-password', authenticateToken, ctrl.changePassword);

module.exports = router;
