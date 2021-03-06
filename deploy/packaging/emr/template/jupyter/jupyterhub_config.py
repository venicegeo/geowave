c = get_config()

import os
pjoin = os.path.join

runtime_dir = pjoin('/srv/jupyterhub')
userlist_loc = pjoin(runtime_dir, 'userlist')
blacklist_loc = pjoin(runtime_dir, 'env_blacklist')
ssl_dir = pjoin(runtime_dir, 'ssl')
if not os.path.exists(ssl_dir):
    os.makedirs(ssl_dir)

# Setup whitelist and admins from file in runtime directory
whitelist = set()
admin = set()
if os.path.isfile(userlist_loc):
    with open(userlist_loc) as f:
        for line in f:
            if not line.strip():
                continue
            parts = line.split()
            name = parts[0].strip()
            whitelist.add(name)
            if len(parts) > 1 and parts[1].strip() == 'admin':
                admin.add(name)

c.Authenticator.whitelist = whitelist
c.Authenticator.admin_users = admin

# Create a blacklist of environment variables to ensure are removed from notebook environments
env_blacklist = []
if os.path.isfile(blacklist_loc):
    with open(blacklist_loc) as f:
        for line in f:
            if not line.strip():
                continue
            line = line.strip()
            env_blacklist.append(line)

for var in os.environ:
    if var not in env_blacklist:
        c.Spawner.env_keep.append(var)


c.JupyterHub.hub_ip = '0.0.0.0'

# Allow administrators to access individual user notebook servers.
c.JupyterHub.admin_access = True

# If SSL certificates exist on cluster uncomment these lines in config.
# Will look in /srv/jupyterhub/ssl/
#c.JupyterHub.ssl_key = pjoin(ssl_dir, 'ssl.key')
#c.JupyterHub.ssl_cert = pjoin(ssl_dir, 'ssl.cert')
c.JupyterHub.port = 9000

# Fix adduser command so it doesn't apply invalid parameters.
c.Authenticator.add_user_cmd = ['adduser']
c.PAMAuthenticator.create_system_users = True

from subprocess import check_call
def copy_notebooks(spawner):
    username = spawner.user.name
    check_call(['/srv/jupyterhub/pre-spawn.sh', username])

c.Spawner.pre_spawn_hook = copy_notebooks
c.Spawner.notebook_dir = u'~/notebooks/'

# Consent to monitor on login page and classification banner on all pages
c.JupyterHub.template_paths = ["/home/ec2-user/gw-jhub-templates/"]
c.JupyterHub.template_vars = {'announcement_login': 'You are accessing a U.S. Government (USG) Information System (IS) that is provided for USG-authorized use only.<p>By using this IS (which includes any device attached to this IS), you consent to the following conditions:<p>The USG routinely intercepts and monitors communications on this IS for purposes including, but not limited to, penetration testing, COMSEC monitoring, network operations and defense, personnel misconduct (PM), law enforcement (LE), and counterintelligence (CI) investigations.<p>At any time, the USG may inspect and seize data stored on this IS.<p>Communications using, or data stored on, this IS are not private, are subject to routine monitoring, interception, and search, and may be disclosed or used for any USG authorized purpose.<p>This IS includes security measures (e.g., authentication and access controls) to protect USG interests - not for your personal benefit or privacy.<p>Notwithstanding the above, using this IS does not constitute consent to PM, LE or CI investigative searching or monitoring of the content of privileged communications, or work product, related to personal representation or services by attorneys, psychotherapists, or clergy, and their assistants. Such communications and work product are private and confidential. See User Agreement for details.</ul>', 'classification_banner_text': 'UNCLASSIFIED//NONE', 'classification_banner_color': 'green'}
