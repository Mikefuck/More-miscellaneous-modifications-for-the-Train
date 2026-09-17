### Task 4: README + export configs to 临时

**Files:**
- Modify: `README.md`
- Create: `D:\Backup\mc mod\临时\habitrain_lottery_config\SETUP.txt`
- Copy: defaults JSON into that folder

- [ ] **Step 1: Update README sections**

Document:
- World layout includes `meta.json`, `skins_quality.json`, `backup_vN/`
- JAR-only auto-seed + version migrate
- **160 coins = 1 draw**
- How to edit configs + `/hlt reload`
- Gold pool design summary (18 pools, 1% gold)

- [ ] **Step 2: Export**

```bat
mkdir "D:\Backup\mc mod\临时\habitrain_lottery_config"
copy /Y "src\main\resources\data\habitrain_lottery\defaults\pools.json" "D:\Backup\mc mod\临时\habitrain_lottery_config\"
copy /Y "src\main\resources\data\habitrain_lottery\defaults\rates.json" "D:\Backup\mc mod\临时\habitrain_lottery_config\"
copy /Y "src\main\resources\data\habitrain_lottery\defaults\grants.json" "D:\Backup\mc mod\临时\habitrain_lottery_config\"
copy /Y "src\main\resources\data\habitrain_lottery\defaults\theme.json" "D:\Backup\mc mod\临时\habitrain_lottery_config\"
copy /Y "src\main\resources\data\habitrain_lottery\defaults\skins_quality.json" "D:\Backup\mc mod\临时\habitrain_lottery_config\"
copy /Y "src\main\resources\data\habitrain_lottery\defaults\meta.json" "D:\Backup\mc mod\临时\habitrain_lottery_config\"
```

Write `SETUP.txt` in Chinese explaining:
1. 只装 JAR 即可，进世界自动生成配置  
2. 若要手动覆盖：把本目录文件复制到 `{世界}/habitrain_lottery/config/` 后执行 `/hlt reload`  
3. 160 金币 = 1 抽  
4. 旧世界会备份到 `backup_v1/` 并迁移  

---
