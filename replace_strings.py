import os
import glob

res_dir = r"c:\Users\dezzy\antigravity\OpenTune\app\src\main\res"
for filepath in glob.glob(os.path.join(res_dir, "values*", "strings.xml")):
    with open(filepath, "r", encoding="utf-8") as f:
        content = f.read()
    
    # We only replace "OpenTune" with "Dezzy" in the visible strings, but we should be careful not to break XML names.
    # Actually, replacing "OpenTune" with "Dezzy" everywhere is safe since it's just the app name.
    new_content = content.replace("OpenTune", "Dezzy")
    
    with open(filepath, "w", encoding="utf-8") as f:
        f.write(new_content)
        
print("Replaced OpenTune with Dezzy in all strings.xml files.")
