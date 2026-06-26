// ============================================================
// routes/devices.js
// ============================================================
'use strict';

const express = require('express');
const router  = express.Router();
const ctrl    = require('../controllers/deviceController');
const { authenticateToken } = require('../middlewares/auth');

// All device routes require a valid JWT
router.use(authenticateToken);

router.get('/',                        ctrl.listDevices);
router.post('/register',               ctrl.registerDevice);
router.delete('/:id',                  ctrl.deleteDevice);
router.post('/:id/status',             ctrl.updateDeviceStatus);
router.post('/:id/location',           ctrl.updateLocation);
router.get('/:id/locations',           ctrl.getLocationHistory);

module.exports = router;
