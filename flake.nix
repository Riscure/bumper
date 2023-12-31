{
  description = "True Code";

  inputs  = {
    nixpkgs.url = github:NixOS/nixpkgs/nixos-22.11;
  };

  outputs = { self, nixpkgs }:
    let
      pkgs = nixpkgs.legacyPackages.x86_64-linux;
    in {
      packages.x86_64-linux = rec {
        bumper = pkgs.callPackage ./.nix/bumper.nix {};
      };

      defaultPackage.x86_64-linux = self.packages.x86_64-linux.bumper;
    };
}
