import requests
import os
import sys

def trigger_workflow():
    url = "https://api.github.com/repos/SEMOSS/Semoss/actions/workflows/104712412/dispatches"
    headers = {
        "Accept": "application/vnd.github+json",
        "Authorization": f"Bearer {os.getenv('GITHUB_TOKEN')}",
        "X-GitHub-Api-Version": "2022-11-28"
    }
    data = {
        "ref": "dev"
    }
    response = requests.post(url, headers=headers, json=data)
    
    if response.status_code not in [200, 204]:
        print(f"Failed to trigger workflow. HTTP Status: {response.status_code}, Response: {response.text}")
        sys.exit(1)
    else:
        print(f"Successfully triggered workflow. HTTP Status: {response.status_code}")

if __name__ == "__main__":
    trigger_workflow()
